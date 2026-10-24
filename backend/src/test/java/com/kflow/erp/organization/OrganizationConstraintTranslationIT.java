package com.kflow.erp.organization;

import com.kflow.erp.organization.api.OrganizationFailure;
import com.kflow.erp.organization.infrastructure.JpaOrganizationStore;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.SQLException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.kflow.erp.organization.api.OrganizationFailure.Code.*;

/** R-01: real database violations plus the production flush translation path, not only SQL rejection. */
@Testcontainers
class OrganizationConstraintTranslationIT {
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16");
    RuntimeException translate(RuntimeException error) {
        var em=mock(EntityManager.class);
        doThrow(error).when(em).flush();
        try { new JpaOrganizationStore(em).flush(); throw new AssertionError("flush must fail"); }
        catch(RuntimeException result) { return result; }
    }
    ConstraintViolationException actualViolation(JdbcTemplate jdbc,String sql,Object... args) {
        var error=catchThrowable(()->jdbc.update(sql,args));
        assertThat(error).isInstanceOf(DataIntegrityViolationException.class);
        Throwable root=error; while(root.getCause()!=null) root=root.getCause();
        assertThat(root).isInstanceOf(PSQLException.class);
        var pg=(PSQLException)root;
        assertThat(pg.getServerErrorMessage()).isNotNull();
        return new ConstraintViolationException("actual PostgreSQL violation",pg,pg.getServerErrorMessage().getConstraint());
    }
    @ParameterizedTest @ValueSource(strings={"department","position"})
    void actualUniqueMapsExactlyWhilePkFkAndCheckAreNotMisclassified(String kind) {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        var jdbc=new JdbcTemplate(ds); String table="organization_"+kind;
        jdbc.update("delete from "+table);
        UUID company=jdbc.queryForObject("select id from organization_company",UUID.class);
        UUID id=UUID.randomUUID();
        String insert="insert into "+table+" values (?,? ,?,?,'ACTIVE',0,now(),now())";
        jdbc.update(insert,id,company,"CODE","이름");
        var unique=actualViolation(jdbc,insert,UUID.randomUUID(),company,"CODE","중복");
        assertThat(unique.getConstraintName()).isEqualTo("uq_organization_"+kind+"_company_code");
        assertThat(translate(unique)).isInstanceOfSatisfying(OrganizationFailure.class,e->assertThat(e.code()).isEqualTo(kind.equals("department")?DEPARTMENT_CODE_CONFLICT:POSITION_CODE_CONFLICT));
        var pk=actualViolation(jdbc,insert,id,company,"OTHER","이름");
        var fk=actualViolation(jdbc,insert,UUID.randomUUID(),UUID.randomUUID(),"OTHER","이름");
        var check=actualViolation(jdbc,insert,UUID.randomUUID(),company,"lowercase","이름");
        assertThat(pk.getSQLState()).isEqualTo("23505");
        assertThat(fk.getSQLState()).isEqualTo("23503");
        assertThat(check.getSQLState()).isEqualTo("23514");
        for(var violation:List.of(pk,fk,check)) {
            assertThat(translate(violation)).isSameAs(violation).isNotInstanceOf(OrganizationFailure.class);
        }
        assertThat(jdbc.queryForObject("select count(*) from "+table,Long.class)).isEqualTo(1);
        System.out.println("O2-EVIDENCE "+kind+": actual named UNIQUE mapped; actual PK/FK/CHECK preserved by production flush translation");
    }
    @Test void unknownOtherUniqueConnectionAndProgrammingErrorsPropagate() {
        for(String constraint:Arrays.asList("uq_other","unknown",null,"")) {
            var error=new ConstraintViolationException("isolated fixture",new SQLException("fixture","23505"),constraint);
            assertThat(translate(error)).isSameAs(error).isNotInstanceOf(OrganizationFailure.class);
        }
        for(var error:List.of(new IllegalStateException("programming fixture"),new org.springframework.dao.DataAccessResourceFailureException("connection fixture")))
            assertThat(translate(error)).isSameAs(error).isNotInstanceOf(OrganizationFailure.class);
    }
}
