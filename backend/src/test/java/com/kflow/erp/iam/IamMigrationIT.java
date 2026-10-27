package com.kflow.erp.iam;

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
class IamMigrationIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16");
    @Test void t30_v6UpgradePreservesEveryPriorRowConstraintAndChecksumWithoutUserSeed(){
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("6").load().migrate();UUID company=jdbc.queryForObject("select id from organization_company",UUID.class);var ids=new ArrayList<UUID>();
        for(String t:List.of("organization_site","organization_department","organization_position")){UUID id=UUID.randomUUID();ids.add(id);jdbc.update("insert into "+t+"(id,company_id,code,name,status,version,created_at,updated_at) values(?,?,'OLD','original','INACTIVE',4,now(),now())",id,company);}
        for(String state:List.of("EMPLOYED","ON_LEAVE","TERMINATED")){
            UUID e=UUID.randomUUID();boolean terminated=state.equals("TERMINATED");
            jdbc.update("insert into hr_employee(id,company_id,employee_no,display_name,employment_status,joined_on,termination_effective_on,termination_reason,auth_revocation_generation,version,created_at,updated_at) values(?,?,?,'original',?,'2026-10-01',?::date,?,9,7,now(),now())",e,company,state,state,terminated?"2026-10-11":null,terminated?"existing synthetic termination":null);
            jdbc.update("insert into hr_employee_assignment(id,company_id,employee_id,site_id,department_id,position_id,effective_from,effective_to,reason,version,created_at,updated_at) values(?,?,?,?,?,?,'2026-10-01',?::date,'original reason',5,now(),now())",UUID.randomUUID(),company,e,ids.get(0),ids.get(1),ids.get(2),terminated?"2026-10-11":null);
        }
        var before=new LinkedHashMap<String,List<Map<String,Object>>>();for(String t:List.of("organization_company","organization_site","organization_department","organization_position","hr_employee","hr_employee_assignment"))before.put(t,jdbc.queryForList("select * from "+t+" order by id"));
        var sums=jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        String oldConstraints="select conname,pg_get_constraintdef(oid) definition from pg_constraint where conrelid in ('organization_company'::regclass,'organization_site'::regclass,'organization_department'::regclass,'organization_position'::regclass,'hr_employee'::regclass,'hr_employee_assignment'::regclass) order by conname";
        var constraints=jdbc.queryForList(oldConstraints);var latest=Flyway.configure().dataSource(ds).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();assertThat(latest.migrate().migrationsExecuted).isZero();
        before.forEach((t,rows)->assertThat(jdbc.queryForList("select * from "+t+" order by id")).isEqualTo(rows));assertThat(jdbc.queryForList(oldConstraints)).isEqualTo(constraints);
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history where version in ('1','2','3','4','5','6') order by installed_rank")).isEqualTo(sums);assertThat(jdbc.queryForObject("select count(*) from iam_user",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select column_default from information_schema.columns where table_name='iam_user' and column_name='auth_revocation_generation'",String.class)).isEqualTo("0");
        try(var context=new SpringApplicationBuilder(KFlowErpApplication.class).web(WebApplicationType.NONE).properties("spring.datasource.url="+postgres.getJdbcUrl(),"spring.datasource.username="+postgres.getUsername(),"spring.datasource.password="+postgres.getPassword(),"spring.jpa.hibernate.ddl-auto=validate").run()){
            assertThat(context.getBean(Flyway.class).info().current().getVersion().toString()).isEqualTo("7");
        }
        System.out.println("I1 T30 V6->V7 existing ORG/HR all rows/constraints/V1-V6 checksums byte values preserved; no User seed; generation default0; Flyway/Hibernate validate passed");
    }
}
