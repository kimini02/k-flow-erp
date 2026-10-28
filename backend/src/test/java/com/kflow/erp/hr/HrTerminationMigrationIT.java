package com.kflow.erp.hr;

import com.kflow.erp.KFlowErpApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.util.PSQLException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class HrTerminationMigrationIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16");
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    UUID company,employee;
    @BeforeEach void setupV5() {
        ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());jdbc=new JdbcTemplate(ds);
        jdbc.execute("drop schema public cascade");jdbc.execute("create schema public");
        Flyway.configure().dataSource(ds).target("5").load().migrate();
        company=jdbc.queryForObject("select id from organization_company",UUID.class);employee=UUID.randomUUID();
        var ids=new ArrayList<UUID>();
        for(String table:List.of("organization_site","organization_department","organization_position")) {
            UUID id=UUID.randomUUID();ids.add(id);
            jdbc.update("insert into "+table+"(id,company_id,code,name,status,version,created_at,updated_at) values(?,?,'OLD','fixture','INACTIVE',7,now(),now())",id,company);
        }
        jdbc.update("insert into hr_employee(id,company_id,employee_no,display_name,employment_status,joined_on,version,created_at,updated_at) values(?,?,'OLD','fixture','EMPLOYED','2026-10-01',8,now(),now())",employee,company);
        jdbc.update("insert into hr_employee_assignment(id,company_id,employee_id,site_id,department_id,position_id,effective_from,reason,version,created_at,updated_at) values(?,?,?,?,?,?,'2026-10-01','original synthetic reason',4,now(),now())",UUID.randomUUID(),company,employee,ids.get(0),ids.get(1),ids.get(2));
    }
    @Test void t32_am02_v5UpgradePreservesRowsConstraintsChecksumsAndAddsGenerationZero() {
        var before=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String t:List.of("organization_company","organization_site","organization_department","organization_position","hr_employee","hr_employee_assignment"))
            before.put(t,jdbc.queryForList("select * from "+t+" order by id"));
        var sums=jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        var constraints=jdbc.queryForList("select conname,pg_get_constraintdef(oid) definition from pg_constraint where conrelid in ('hr_employee'::regclass,'hr_employee_assignment'::regclass) order by conname");
        var migration=Flyway.configure().dataSource(ds).target("6").load();assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);migration.validate();
        assertThat(migration.migrate().migrationsExecuted).isZero();
        before.forEach((table,rows)->{var after=jdbc.queryForList("select * from "+table+" order by id");
            if(table.equals("hr_employee"))after.forEach(r->{assertThat(r.remove("auth_revocation_generation")).isEqualTo(0L);assertThat(r.remove("termination_reason")).isNull();});
            assertThat(after).isEqualTo(rows);
        });
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history where version in ('1','2','3','4','5') order by installed_rank")).isEqualTo(sums);
        assertThat(jdbc.queryForList("select conname,pg_get_constraintdef(oid) definition from pg_constraint where conrelid in ('hr_employee'::regclass,'hr_employee_assignment'::regclass) and conname not in ('ck_hr_employee_auth_revocation_generation','ck_hr_employee_termination_reason') order by conname")).isEqualTo(constraints);
        try(var context=new SpringApplicationBuilder(KFlowErpApplication.class).web(WebApplicationType.NONE)
                .properties("spring.datasource.url="+postgres.getJdbcUrl(),"spring.datasource.username="+postgres.getUsername(),"spring.datasource.password="+postgres.getPassword(),"spring.jpa.hibernate.ddl-auto=validate").run()) {
            assertThat(context.getBean(Flyway.class).info().current().getVersion().toString()).isEqualTo("10");
        }
        System.out.println("H34-04 EVIDENCE T32 V5->V6: existing Employee/Assignment/ORG all columns preserved; generation0/reasonNULL; V1-V5 checksums and old constraints identical; Flyway and Hibernate validate passed");
    }
    @Test void t32_terminatedWithoutRealReasonStopsUpgradeWithoutFabricatingData() {
        jdbc.update("update hr_employee_assignment set effective_to='2026-10-11' where employee_id=?",employee);
        jdbc.update("update hr_employee set employment_status='TERMINATED',termination_effective_on='2026-10-11' where id=?",employee);
        var before=jdbc.queryForList("select * from hr_employee");var rows=jdbc.queryForList("select * from hr_employee_assignment");
        var sums=jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        var error=catchThrowable(()->Flyway.configure().dataSource(ds).load().migrate());
        PSQLException sql=null;for(Throwable c=error;c!=null;c=c.getCause())if(c instanceof PSQLException p){sql=p;break;}
        assertThat((Throwable)sql).isNotNull();assertThat(sql.getSQLState()).isEqualTo("23514");assertThat(sql.getServerErrorMessage().getConstraint()).isEqualTo("ck_hr_employee_termination_reason");
        assertThat(jdbc.queryForList("select * from hr_employee")).isEqualTo(before);assertThat(jdbc.queryForList("select * from hr_employee_assignment")).isEqualTo(rows);
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank")).isEqualTo(sums);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='public' and table_name='hr_employee' and column_name in ('termination_reason','auth_revocation_generation')",Integer.class)).isZero();
        Flyway.configure().dataSource(ds).target("5").load().validate();
        System.out.println("H34-04 EVIDENCE T32 V5 TERMINATED without reason: V6 abort23514/ck_hr_employee_termination_reason; migration atomic rollback, all rows/checksums intact, no backfill/delete/constraint disabling");
    }
}
