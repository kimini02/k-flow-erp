package com.kflow.erp.organization;

import com.kflow.erp.KFlowErpApplication;
import com.kflow.erp.organization.api.OrganizationReferenceQueries;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OrganizationV3MigrationIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16");
    @Test void v1AndV2DataAndChecksumsSurviveV3WithUnknownReasonsKeptNull() {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=new JdbcTemplate(ds);
        assertThat(Flyway.configure().dataSource(ds).target("2").load().migrate().migrationsExecuted).isEqualTo(2);
        UUID company=jdbc.queryForObject("select id from organization_company",UUID.class);
        jdbc.update("update organization_company set name='existing company',version=7");
        var originals=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:List.of("organization_site","organization_department","organization_position")) {
            jdbc.update("insert into "+table+" values (?,?,'OLD','old name','INACTIVE',9,now(),now())",UUID.randomUUID(),company);
            jdbc.update("insert into "+table+" values (?,?,'LIVE','live name','ACTIVE',2,now(),now())",UUID.randomUUID(),company);
            originals.put(table,jdbc.queryForList("select * from "+table+" order by code"));
        }
        var oldCompany=jdbc.queryForMap("select * from organization_company");
        var checksums=jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        var flyway=Flyway.configure().dataSource(ds).target("3").load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("3");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForMap("select * from organization_company")).isEqualTo(oldCompany);
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history where version in ('1','2') order by installed_rank")).isEqualTo(checksums);
        originals.forEach((table,rows)->{
            assertThat(jdbc.queryForList("select id,company_id,code,name,status,version,created_at,updated_at from "+table+" order by code")).isEqualTo(rows);
            assertThat(jdbc.queryForObject("select count(*) from "+table+" where state_changed_at is not null or last_state_change_reason is not null",Integer.class)).isZero();
        });
        try(var context=new SpringApplicationBuilder(KFlowErpApplication.class).web(WebApplicationType.NONE)
                .properties("spring.datasource.url="+postgres.getJdbcUrl(),"spring.datasource.username="+postgres.getUsername(),
                        "spring.datasource.password="+postgres.getPassword(),"spring.jpa.hibernate.ddl-auto=validate").run()) {
            assertThat(context.getBean(OrganizationReferenceQueries.class).getCompany().name()).isEqualTo("existing company");
        }
        System.out.println("O3-EVIDENCE V1/V2 checksums and existing Company/Site/Department/Position values preserved; V3 only adds nullable evidence pair; Flyway/Hibernate validated");
    }
}
