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
class UserRoleMigrationIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16");
    @Test void t36_v8UpgradePreservesEveryOriginalRowAndChecksumAdditiveParentKeysAndEmptyGrants(){
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("8").load().migrate();UUID company=jdbc.queryForObject("select id from organization_company",UUID.class);var ids=new ArrayList<UUID>();
        for(String t:List.of("organization_site","organization_department","organization_position")){UUID id=UUID.randomUUID();ids.add(id);jdbc.update("insert into "+t+"(id,company_id,code,name,status,version,created_at,updated_at) values(?,?,'OLD','original','INACTIVE',4,now(),now())",id,company);}
        for(String state:List.of("EMPLOYED","ON_LEAVE","TERMINATED")){
            UUID e=UUID.randomUUID();boolean terminated=state.equals("TERMINATED");
            jdbc.update("insert into hr_employee(id,company_id,employee_no,display_name,employment_status,joined_on,termination_effective_on,termination_reason,auth_revocation_generation,version,created_at,updated_at) values(?,?,?,'original',?,'2026-10-01',?::date,?,9,7,now(),now())",e,company,state,state,terminated?"2026-10-11":null,terminated?"existing synthetic termination":null);
            jdbc.update("insert into hr_employee_assignment(id,company_id,employee_id,site_id,department_id,position_id,effective_from,effective_to,reason,version,created_at,updated_at) values(?,?,?,?,?,?,'2026-10-01',?::date,'original reason',5,now(),now())",UUID.randomUUID(),company,e,ids.get(0),ids.get(1),ids.get(2),terminated?"2026-10-11":null);
        }
        for(var row:jdbc.queryForList("select id,employment_status from hr_employee order by id"))jdbc.update("insert into iam_user(id,company_id,employee_id,login_key,account_status,version,auth_revocation_generation,creation_reason,created_at,updated_at) values(?,?,?,?, 'DISABLED',5,8,'original user reason',now(),now())",UUID.randomUUID(),company,row.get("id"),row.get("employment_status").toString().toLowerCase(Locale.ROOT));
        for(String state:List.of("ACTIVE","INACTIVE"))jdbc.update("insert into iam_role(id,company_id,code,name,status,version,creation_reason,last_change_reason,last_change_kind,created_at,updated_at) values(?,?,?,'original role',?,5,'created','existing last change','RENAME',now(),now())",UUID.randomUUID(),company,state,state);
        var tables=List.of("organization_company","organization_site","organization_department","organization_position","hr_employee","hr_employee_assignment","iam_user","iam_role");var before=new LinkedHashMap<String,List<Map<String,Object>>>();for(String t:tables)before.put(t,jdbc.queryForList("select * from "+t+" order by id"));
        var sums=jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        String oldConstraints="select conname,conrelid::regclass::text relation,pg_get_constraintdef(oid) definition from pg_constraint where conrelid in ('organization_company'::regclass,'organization_site'::regclass,'organization_department'::regclass,'organization_position'::regclass,'hr_employee'::regclass,'hr_employee_assignment'::regclass,'iam_user'::regclass,'iam_role'::regclass) order by conname";
        var constraints=jdbc.queryForList(oldConstraints);var latest=Flyway.configure().dataSource(ds).target("9").load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();assertThat(latest.migrate().migrationsExecuted).isZero();
        before.forEach((t,rows)->assertThat(jdbc.queryForList("select * from "+t+" order by id")).as(t).isEqualTo(rows));var afterConstraints=jdbc.queryForList(oldConstraints);assertThat(afterConstraints).containsAll(constraints).hasSize(constraints.size()+2);assertThat(afterConstraints.stream().filter(r->!constraints.contains(r)).map(r->r.get("conname"))).containsExactlyInAnyOrder("uq_iam_user_company_id","uq_iam_role_company_id");
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history where version in ('1','2','3','4','5','6','7','8') order by installed_rank")).isEqualTo(sums);assertThat(jdbc.queryForObject("select count(*) from iam_user_role",Integer.class)).isZero();
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='iam_user_role' order by ordinal_position",String.class)).containsExactlyInAnyOrder("id","company_id","user_id","role_id","granted_at","grant_reason","revoked_at","revoke_reason","revoke_kind","version");
        assertThat(jdbc.queryForList("select conname from pg_constraint where conrelid='iam_user_role'::regclass and contype in ('p','f','u','c') order by conname",String.class)).contains("pk_iam_user_role","fk_iam_user_role_user","fk_iam_user_role_role","ck_iam_user_role_version","ck_iam_user_role_grant_reason","ck_iam_user_role_revocation","ck_iam_user_role_timestamps");
        try(var context=new SpringApplicationBuilder(KFlowErpApplication.class).web(WebApplicationType.NONE).properties("spring.datasource.url="+postgres.getJdbcUrl(),"spring.datasource.username="+postgres.getUsername(),"spring.datasource.password="+postgres.getPassword(),"spring.jpa.hibernate.ddl-auto=validate").run()){
            assertThat(context.getBean(Flyway.class).info().current().getVersion().toString()).isEqualTo("10");assertThat(context.getBean(jakarta.persistence.EntityManagerFactory.class).getMetamodel().getEntities()).extracting(jakarta.persistence.metamodel.EntityType::getName).contains("IamUserRole");
        }
        System.out.println("I3 T36 V8->V9 exact prior rows="+before+" original constraint definitions preserved="+constraints+" original Flyway checksum rows="+sums+" only two additive parent unique keys, empty grant table; Flyway/Hibernate validate passed");
    }
}
