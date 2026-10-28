package com.kflow.erp.iam.credentials;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;

class SessionMigrationIT extends AuthenticationTestSupport {
  @Test
  void t30_v10UpgradePreservesAllDomainRowsAndChecksumsAndValidatesOfficialSchema()
      throws Exception {
    try (var official =
            getClass()
                .getResourceAsStream("/org/springframework/session/jdbc/schema-postgresql.sql");
        var ours = getClass().getResourceAsStream("/db/migration/V11__jdbc_sessions.sql")) {
      assertThat(official).isNotNull();
      assertThat(ours).isNotNull();
      assertThat(ours.readAllBytes()).isEqualTo(official.readAllBytes());
    }
    ready("migration");
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
            "iam_user_role",
            "iam_credential_enrollment",
            "iam_password_credential",
            "iam_bootstrap_record");
    String schema = "a2_upgrade";
    jdbc.execute("create schema " + schema);
    try {
      var old =
          Flyway.configure()
              .dataSource(jdbc.getDataSource())
              .schemas(schema)
              .defaultSchema(schema)
              .target("10")
              .load();
      old.migrate();
      for (String table : tables)
        if (!table.equals("organization_company"))
          jdbc.execute("insert into " + schema + "." + table + " select * from public." + table);
      var before = new LinkedHashMap<String, List<Map<String, Object>>>();
      for (String table : tables)
        before.put(table, jdbc.queryForList("select * from " + schema + "." + table));
      var checksums =
          jdbc.queryForList(
              "select version,checksum from "
                  + schema
                  + ".flyway_schema_history order by installed_rank");
      var latest =
          Flyway.configure()
              .dataSource(jdbc.getDataSource())
              .schemas(schema)
              .defaultSchema(schema)
              .load();
      assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
      latest.validate();
      assertThat(latest.migrate().migrationsExecuted).isZero();
      for (String table : tables) {
        var after = jdbc.queryForList("select * from " + schema + "." + table);
        assertThat(after.size()).isEqualTo(before.get(table).size());
        for (int i = 0; i < after.size(); i++) same(before.get(table).get(i), after.get(i));
      }
      assertThat(
              jdbc.queryForList(
                  "select version,checksum from "
                      + schema
                      + ".flyway_schema_history where version<>'11' order by installed_rank"))
          .isEqualTo(checksums);
      assertThat(
              jdbc.queryForList(
                  "select"
                      + " column_name||':'||data_type||':'||coalesce(character_maximum_length::text,'-')||':'||is_nullable"
                      + " from information_schema.columns where table_schema=? and"
                      + " table_name='spring_session' order by ordinal_position",
                  String.class,
                  schema))
          .containsExactly(
              "primary_id:character:36:NO",
              "session_id:character:36:NO",
              "creation_time:bigint:-:NO",
              "last_access_time:bigint:-:NO",
              "max_inactive_interval:integer:-:NO",
              "expiry_time:bigint:-:NO",
              "principal_name:character varying:100:YES");
      assertThat(
              jdbc.queryForList(
                  "select"
                      + " column_name||':'||data_type||':'||coalesce(character_maximum_length::text,'-')||':'||is_nullable"
                      + " from information_schema.columns where table_schema=? and"
                      + " table_name='spring_session_attributes' order by ordinal_position",
                  String.class,
                  schema))
          .containsExactly(
              "session_primary_id:character:36:NO",
              "attribute_name:character varying:200:NO",
              "attribute_bytes:bytea:-:NO");
      assertThat(
              jdbc.queryForList(
                  "select indexname from pg_indexes where schemaname=? and"
                      + " tablename='spring_session'",
                  schema))
          .hasSize(4);
      var fk =
          jdbc.queryForMap(
              "select confdeltype,confupdtype,condeferrable from pg_constraint where"
                  + " conname='spring_session_attributes_fk' and connamespace=?::regnamespace",
              schema);
      assertThat(fk.get("confdeltype")).isEqualTo("c");
      assertThat(fk.get("confupdtype")).isEqualTo("a");
      assertThat(fk.get("condeferrable")).isEqualTo(false);
      System.out.println(
          "A2 T30 V10 -> V11 official Session schema: two tables, seven parent columns, four"
              + " indexes, attributes composite PK / FK ON DELETE CASCADE; old rows/checksums"
              + " preserved");
    } finally {
      jdbc.execute("drop schema " + schema + " cascade");
    }
  }

  @Test
  void t30_actualPrimaryUniqueAndForeignKeysWithPreciseSqlstates() {
    String id = UUID.randomUUID().toString(), external = UUID.randomUUID().toString();
    jdbc.update("insert into spring_session values(?,?,1,1,60,60001,null)", id, external);
    for (String constraint :
        List.of(
            "spring_session_pk",
            "spring_session_ix1",
            "spring_session_attributes_fk",
            "spring_session_attributes_pk")) {
      Throwable error =
          catchThrowable(
              () -> {
                switch (constraint) {
                  case "spring_session_pk" ->
                      jdbc.update(
                          "insert into spring_session values(?,?,1,1,60,60001,null)",
                          id,
                          UUID.randomUUID().toString());
                  case "spring_session_ix1" ->
                      jdbc.update(
                          "insert into spring_session values(?,?,1,1,60,60001,null)",
                          UUID.randomUUID().toString(),
                          external);
                  case "spring_session_attributes_fk" ->
                      jdbc.update(
                          "insert into spring_session_attributes values(?,'test',?)",
                          UUID.randomUUID().toString(),
                          new byte[] {1});
                  default -> {
                    jdbc.update(
                        "insert into spring_session_attributes values(?,'test',?)",
                        id,
                        new byte[] {1});
                    jdbc.update(
                        "insert into spring_session_attributes values(?,'test',?)",
                        id,
                        new byte[] {1});
                  }
                }
              });
      org.postgresql.util.PSQLException pg = null;
      for (Throwable c = error; c != null; c = c.getCause())
        if (c instanceof org.postgresql.util.PSQLException p) pg = p;
      assertThat((Throwable) pg).isNotNull();
      assertThat(pg.getSQLState()).isEqualTo(constraint.endsWith("_fk") ? "23503" : "23505");
      assertThat(pg.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
      System.out.println("A2 T30 constraint=" + constraint + " SQLSTATE=" + pg.getSQLState());
    }
    jdbc.update("delete from spring_session where primary_id=?", id);
    assertThat(count("spring_session_attributes")).isZero();
  }
}
