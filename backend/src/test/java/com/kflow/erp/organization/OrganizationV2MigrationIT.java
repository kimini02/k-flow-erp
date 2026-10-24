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
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class OrganizationV2MigrationIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16");
    @Test void existingV1CompanyAndSiteSurviveV2AndHibernateValidation() {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        var jdbc=new JdbcTemplate(ds);
        var v1=Flyway.configure().dataSource(ds).target("1").load();
        assertThat(v1.migrate().migrationsExecuted).isEqualTo(1);
        UUID company=jdbc.queryForObject("select id from organization_company",UUID.class);
        UUID site=UUID.randomUUID();
        jdbc.update("update organization_company set name='기존 회사명', version=4");
        jdbc.update("insert into organization_site values (?,?, 'HQ','기존 사업장','INACTIVE',3,now(),now())",site,company);
        var companyBefore=jdbc.queryForMap("select * from organization_company");
        var siteBefore=jdbc.queryForMap("select * from organization_site");
        var checksum=jdbc.queryForObject("select checksum from flyway_schema_history where version='1'",Integer.class);
        var latest=Flyway.configure().dataSource(ds).target("2").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(latest.validateWithResult().validationSuccessful).isTrue();
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForMap("select * from organization_company")).isEqualTo(companyBefore);
        assertThat(jdbc.queryForMap("select * from organization_site")).isEqualTo(siteBefore);
        assertThat(jdbc.queryForObject("select checksum from flyway_schema_history where version='1'",Integer.class)).isEqualTo(checksum);
        assertThat(jdbc.queryForObject("select count(*) from organization_department",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from organization_position",Long.class)).isZero();
        try(var context=new SpringApplicationBuilder(KFlowErpApplication.class).web(WebApplicationType.NONE)
                .properties("spring.datasource.url="+postgres.getJdbcUrl(),"spring.datasource.username="+postgres.getUsername(),
                        "spring.datasource.password="+postgres.getPassword(),"spring.jpa.hibernate.ddl-auto=validate").run()) {
            var queries=context.getBean(OrganizationReferenceQueries.class);
            assertThat(queries.getCompany().name()).isEqualTo("기존 회사명");
            assertThat(queries.findSite(company,site).orElseThrow().status()).isEqualTo("INACTIVE");
            assertThat(queries.findDepartment(company,UUID.randomUUID())).isEmpty();
            assertThat(queries.findPosition(company,UUID.randomUUID())).isEmpty();
        }
        System.out.println("O2-EVIDENCE V1 -> V2 upgrade: Company/Site rows and V1 checksum preserved, Flyway and Hibernate validated");
    }
}
