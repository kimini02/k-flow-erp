package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;

import com.kflow.erp.KFlowErpApplication;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class CredentialMigrationIT {
  @Container static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

  @Test
  void t34_v9UpgradePreservesAllRowsChecksumsAndConstraints() {
    var ds =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    var jdbc = new JdbcTemplate(ds);
    Flyway.configure().dataSource(ds).target("9").load().migrate();
    UUID company = jdbc.queryForObject("select id from organization_company", UUID.class);
    var ids = new ArrayList<UUID>();
    for (String t :
        List.of("organization_site", "organization_department", "organization_position")) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      jdbc.update(
          "insert into "
              + t
              + "(id,company_id,code,name,status,version,created_at,updated_at)"
              + " values(?,?,'OLD','original','INACTIVE',4,now(),now())",
          id,
          company);
    }
    for (String state : List.of("EMPLOYED", "ON_LEAVE", "TERMINATED")) {
      UUID e = UUID.randomUUID();
      boolean terminated = state.equals("TERMINATED");
      jdbc.update(
          "insert into"
              + " hr_employee(id,company_id,employee_no,display_name,employment_status,joined_on,termination_effective_on,termination_reason,auth_revocation_generation,version,created_at,updated_at)"
              + " values(?,?,?,'original',?,'2026-10-01',?::date,?,9,7,now(),now())",
          e,
          company,
          state,
          state,
          terminated ? "2026-10-11" : null,
          terminated ? "existing synthetic termination" : null);
      jdbc.update(
          "insert into"
              + " hr_employee_assignment(id,company_id,employee_id,site_id,department_id,position_id,effective_from,effective_to,reason,version,created_at,updated_at)"
              + " values(?,?,?,?,?,?,'2026-10-01',?::date,'original reason',5,now(),now())",
          UUID.randomUUID(),
          company,
          e,
          ids.get(0),
          ids.get(1),
          ids.get(2),
          terminated ? "2026-10-11" : null);
    }
    for (var row : jdbc.queryForList("select id,employment_status from hr_employee order by id"))
      jdbc.update(
          "insert into"
              + " iam_user(id,company_id,employee_id,login_key,account_status,version,auth_revocation_generation,creation_reason,created_at,updated_at)"
              + " values(?,?,?,?, 'DISABLED',5,8,'original user reason',now(),now())",
          UUID.randomUUID(),
          company,
          row.get("id"),
          row.get("employment_status").toString().toLowerCase(Locale.ROOT));
    for (String state : List.of("ACTIVE", "INACTIVE"))
      jdbc.update(
          "insert into"
              + " iam_role(id,company_id,code,name,status,version,creation_reason,last_change_reason,last_change_kind,created_at,updated_at)"
              + " values(?,?,?,'original role',?,5,'created','existing last"
              + " change','RENAME',now(),now())",
          UUID.randomUUID(),
          company,
          state,
          state);

    UUID user = jdbc.queryForObject("select id from iam_user order by id limit 1", UUID.class),
        role = jdbc.queryForObject("select id from iam_role where status='ACTIVE'", UUID.class);
    jdbc.update(
        "insert into iam_user_role(id,company_id,user_id,role_id,granted_at,grant_reason,version)"
            + " values(?,?,?,?,now(),'existing grant',0)",
        UUID.randomUUID(),
        company,
        user,
        role);
    var tables =
        List.of(
            "organization_company",
            "organization_site",
            "organization_department",
            "organization_position",
            "hr_employee",
            "hr_employee_assignment",
            "iam_user",
            "iam_role",
            "iam_user_role");
    var before = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (String t : tables) before.put(t, jdbc.queryForList("select * from " + t + " order by id"));
    var sums =
        jdbc.queryForList(
            "select version,checksum from flyway_schema_history order by installed_rank");
    String catalog =
        "select conname,conrelid::regclass::text relation,pg_get_constraintdef(oid) definition from"
            + " pg_constraint where connamespace='public'::regnamespace and conrelid not in"
            + " ('flyway_schema_history'::regclass) order by conname";
    var constraints = jdbc.queryForList(catalog);
    var latest = Flyway.configure().dataSource(ds).target("10").load();
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
    latest.validate();
    assertThat(latest.migrate().migrationsExecuted).isZero();
    before.forEach(
        (t, rows) ->
            assertThat(jdbc.queryForList("select * from " + t + " order by id")).isEqualTo(rows));
    assertThat(jdbc.queryForList(catalog)).containsAll(constraints);
    assertThat(
            jdbc.queryForList(
                "select version,checksum from flyway_schema_history where version<>'10' order by"
                    + " installed_rank"))
        .isEqualTo(sums);
    for (String t :
        List.of("iam_password_credential", "iam_credential_enrollment", "iam_bootstrap_record"))
      assertThat(jdbc.queryForObject("select count(*) from " + t, Integer.class)).isZero();
    try (var context =
        new SpringApplicationBuilder(KFlowErpApplication.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.datasource.url=" + postgres.getJdbcUrl(),
                "spring.datasource.username=" + postgres.getUsername(),
                "spring.datasource.password=" + postgres.getPassword(),
                "spring.jpa.hibernate.ddl-auto=validate")
            .run()) {
      assertThat(context.getBean(Flyway.class).info().current().getVersion().toString())
          .isEqualTo("11");
    }
    System.out.println(
        "A1 T34 V9 -> V10 exact original rows and constraints preserved; V1-V9 checksums="
            + sums
            + "; three empty new tables; Flyway and Hibernate validation passed");
  }
}
