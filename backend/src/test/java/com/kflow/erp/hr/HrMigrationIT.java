package com.kflow.erp.hr;

import com.kflow.erp.KFlowErpApplication;
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
class HrMigrationIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16");
    @Test void t29_v3UpgradePreservesAllOrganizationRowsAndChecksumsAndAddsOnlyApprovedHrSchema() {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("3").load().migrate();
        UUID company=jdbc.queryForObject("select id from organization_company",UUID.class);
        var before=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String name:List.of("site","department","position")) {
            String table="organization_"+name;
            jdbc.update("insert into "+table+"(id,company_id,code,name,status,version,created_at,updated_at,state_changed_at,last_state_change_reason) values(?,?,'OLD','preserved','INACTIVE',7,now(),now(),now(),'original reason')",UUID.randomUUID(),company);
            before.put(table,jdbc.queryForList("select * from "+table+" order by id"));
        }
        before.put("organization_company",jdbc.queryForList("select * from organization_company"));
        var sums=jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        var latest=Flyway.configure().dataSource(ds).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(6);
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();assertThat(latest.migrate().migrationsExecuted).isZero();
        before.forEach((table,rows)->assertThat(jdbc.queryForList("select * from "+table+" order by id")).isEqualTo(rows));
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history where version in ('1','2','3') order by installed_rank")).isEqualTo(sums);
        assertThat(jdbc.queryForObject("select count(*) from pg_extension where extname='btree_gist'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select condeferrable from pg_constraint where conname='ex_hr_assignment_employee_period'",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname in ('uq_organization_site_company_id','uq_organization_department_company_id','uq_organization_position_company_id','uq_hr_employee_company_id')",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from hr_employee",Integer.class)).isZero();
        try(var context=new SpringApplicationBuilder(KFlowErpApplication.class).web(WebApplicationType.NONE)
                .properties("spring.datasource.url="+postgres.getJdbcUrl(),"spring.datasource.username="+postgres.getUsername(),"spring.datasource.password="+postgres.getPassword(),"spring.jpa.hibernate.ddl-auto=validate").run()) {
            assertThat(context.getBean(Flyway.class).info().current().getVersion().toString()).isEqualTo("9");
        }
        System.out.println("H12-EVIDENCE V1/V2/V3 rows and checksums preserved; V4+V5+V6+V7+V8 applied; btree_gist and NOT DEFERRABLE EXCLUDE present; Hibernate validate passed");
    }
}
