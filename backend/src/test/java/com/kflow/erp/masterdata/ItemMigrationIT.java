package com.kflow.erp.masterdata;

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
class ItemMigrationIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16");
    @Test void t23_v11UpgradePreservesPriorRowsConstraintsChecksumsAndAddsOnlyEmptyItemTable() {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).target("11").load().migrate();UUID company=jdbc.queryForObject("select id from organization_company",UUID.class);
        for(String table:List.of("organization_site","organization_department","organization_position"))
            jdbc.update("insert into "+table+"(id,company_id,code,name,status,version,created_at,updated_at) values(?,?,'OLD','preserved','INACTIVE',7,now(),now())",UUID.randomUUID(),company);
        jdbc.update("insert into iam_role(id,company_id,code,name,status,version,creation_reason,created_at,updated_at) values(?,?,'OLD','preserved','INACTIVE',8,'original',now(),now())",UUID.randomUUID(),company);
        var names=jdbc.queryForList("select tablename from pg_tables where schemaname='public' and tablename<>'flyway_schema_history' order by tablename",String.class);
        var rows=new LinkedHashMap<String,List<String>>();
        names.forEach(table->rows.put(table,jdbc.queryForList("select row_to_json(t)::text from "+table+" t order by row_to_json(t)::text",String.class)));
        var checksums=jdbc.queryForList("select version,checksum from flyway_schema_history order by installed_rank");
        String constraintsSql="select c.conname,pg_get_constraintdef(c.oid) definition from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname='public' and c.conrelid<>'flyway_schema_history'::regclass order by c.conname";
        var constraints=jdbc.queryForList(constraintsSql);
        var latest=Flyway.configure().dataSource(ds).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();assertThat(latest.migrate().migrationsExecuted).isZero();
        rows.forEach((table,before)->assertThat(jdbc.queryForList("select row_to_json(t)::text from "+table+" t order by row_to_json(t)::text",String.class)).as(table).isEqualTo(before));
        assertThat(jdbc.queryForList("select version,checksum from flyway_schema_history where version<>'12' order by installed_rank")).isEqualTo(checksums);
        assertThat(jdbc.queryForList(constraintsSql)).containsAll(constraints);
        var after=jdbc.queryForList("select tablename from pg_tables where schemaname='public' and tablename<>'flyway_schema_history' order by tablename",String.class);
        assertThat(after).containsAll(names).hasSize(names.size()+1);assertThat(after).contains("masterdata_item");
        assertThat(jdbc.queryForObject("select count(*) from masterdata_item",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pg_indexes where tablename='masterdata_item'",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select numeric_scale from information_schema.columns where table_name='masterdata_item' and column_name='reference_price'",Integer.class)).isNull();
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name='masterdata_item'",String.class))
                .containsExactlyInAnyOrder("id","company_id","code","name","specification","category","usage_profile","base_uom","stock_managed","reference_price","status","version","first_used_at","first_use_kind","first_use_source_id","created_at","updated_at");
        try(var context=new SpringApplicationBuilder(KFlowErpApplication.class).web(WebApplicationType.NONE)
                .properties("spring.datasource.url="+postgres.getJdbcUrl(),"spring.datasource.username="+postgres.getUsername(),"spring.datasource.password="+postgres.getPassword(),"spring.jpa.hibernate.ddl-auto=validate").run()) {
            assertThat(context.getBean(Flyway.class).info().applied()).hasSize(12);
            assertThat(context.getBean(jakarta.persistence.EntityManagerFactory.class).isOpen()).isTrue();
        }
        System.out.println("ITEM V11 -> V12: all prior tables/rows/constraints and checksums preserved="+checksums+"; no Item seed; fixed Unit catalog; native Item schema checked independently of Hibernate entity validation");
    }
}
