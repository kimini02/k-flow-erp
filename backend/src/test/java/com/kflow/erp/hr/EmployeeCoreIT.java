package com.kflow.erp.hr;

import com.kflow.erp.KFlowErpApplication;
import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.application.*;
import com.kflow.erp.hr.domain.*;
import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.application.*;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.kflow.testprobe.ManagedProbe;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.kflow.erp.hr.api.HrFailure.Code.*;

@Testcontainers
@SpringBootTest(classes=KFlowErpApplication.class)
@Import(EmployeeCoreIT.ProbeConfiguration.class)
@Timeout(60)
class EmployeeCoreIT {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16")
            .withInitScript("org03-probe.sql").withCommand("postgres","-c","log_statement=all","-c","log_line_prefix=%p %x ");
    @TestConfiguration @EntityScan(basePackageClasses={KFlowErpApplication.class,ManagedProbe.class})
    static class ProbeConfiguration {}
    @Autowired EmployeeRegistrationService registration;
    @Autowired EmployeeQueryService queries;
    @Autowired OrganizationService organization;
    @Autowired OrganizationStateService states;
    @Autowired OrganizationAssignmentGuard guard;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoSpyBean HrStore store;
    @MockitoSpyBean OrganizationLockStore locks;
    @MockitoBean(name="hrClock") Clock clock;
    final LocalDate today=LocalDate.of(2026,10,26);
    final Instant instant=Instant.parse("2026-10-26T03:00:00Z");
    UUID company,site,department,position;
    ExecutorService pool;
    List<CountDownLatch> gates=new ArrayList<>();
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(instant);
        jdbc.update("delete from hr_employee_assignment"); jdbc.update("delete from hr_employee");
        jdbc.update("delete from org03_probe.org03_test_probe");
        for(String t:List.of("site","department","position")) jdbc.update("delete from organization_"+t);
        company=organization.getCompany().companyId();
        site=organization.registerSite("S","사업장").siteId();
        department=organization.registerDepartment("D","개발팀").departmentId();
        position=organization.registerPosition("P","사원").positionId();
        pool=Executors.newFixedThreadPool(4);
    }
    @AfterEach void cleanup() throws Exception {
        gates.forEach(CountDownLatch::countDown); pool.shutdownNow(); assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();
    }
    CreateEmployee input(String no) {return new CreateEmployee(company,no,"김민희",today,site,department,position,"입사");}
    EmployeeCreated create(String no) {return registration.createEmployee(input(no));}
    void failure(HrFailure.Code code,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(HrFailure.class,e->assertThat(e.code()).isEqualTo(code));
    }
    void pairCount(int n) {
        assertThat(jdbc.queryForObject("select count(*) from hr_employee",Integer.class)).isEqualTo(n);
        assertThat(jdbc.queryForObject("select count(*) from hr_employee_assignment",Integer.class)).isEqualTo(n);
    }
    <T> T tx(Supplier<T> action) {return new TransactionTemplate(manager).execute(s->action.get());}
    CountDownLatch gate(){var g=new CountDownLatch(1);gates.add(g);return g;}
    void await(CountDownLatch g) {try{assertThat(g.await(12,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    Future<Object> job(Supplier<?> action) {return pool.submit(()->{try{return action.get();}catch(RuntimeException e){return e;}});}
    Object result(Future<Object> f) {try{return f.get(15,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}}
    void blocked(String query) {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
        while(System.nanoTime()<until) {
            if(jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and cardinality(pg_blocking_pids(pid))>0 and query ilike ?",Integer.class,"%"+query+"%")>0) {
                System.out.println("H12-EVIDENCE actual PostgreSQL wait: "+query);return;
            }
            try{Thread.sleep(15);}catch(InterruptedException e){throw new AssertionError(e);}
        }
        fail("No PostgreSQL blocking observed: "+query);
    }
    UUID target(OrganizationTarget kind){return switch(kind){case SITE->site;case DEPARTMENT->department;case POSITION->position;};}
    OrganizationStateResult deactivate(OrganizationTarget kind) {
        return switch(kind){
            case SITE->states.deactivateSite(company,site,0L,"중지");
            case DEPARTMENT->states.deactivateDepartment(company,department,0L,"중지");
            case POSITION->states.deactivatePosition(company,position,0L,"중지");
        };
    }
    PSQLException pg(Throwable failure) {
        for(Throwable c=failure;c!=null;c=c.getCause()) if(c instanceof PSQLException p)return p;
        throw new AssertionError("Expected real PostgreSQL error",failure);
    }
    void sqlFailure(String state,String constraint,Runnable action) {
        var p=pg(catchThrowable(action::run));assertThat(p.getSQLState()).isEqualTo(state);
        if(constraint!=null)assertThat(p.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
    }
    UUID fixtureAssignment(UUID employee,LocalDate from,LocalDate to) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into hr_employee_assignment(id,company_id,employee_id,site_id,department_id,position_id,effective_from,effective_to,reason,version,created_at,updated_at) values(?,?,?,?,?,?,?,?,'fixture',0,now(),now())",
                id,company,employee,site,department,position,from,to);return id;
    }
    void close(UUID assignment,LocalDate to) {jdbc.update("update hr_employee_assignment set effective_to=? where id=?",to,assignment);}

    @Test void t01_actualAtomicPairAndMinimumIdentityPorts() {
        var created=create(" e-01 ");pairCount(1);
        assertThat(created.employee().employeeNo()).isEqualTo("E-01");assertThat(created.employee().version()).isZero();
        assertThat(created.assignmentVersion()).isZero();assertThat(created.employee().id().version()).isEqualTo(4);
        assertThat(created.assignmentId().version()).isEqualTo(4);assertThat(created.employee().companyId()).isEqualTo(company);
        assertThat(queries.findEmployee(company,created.employee().id())).contains(created.employee());
        var a=queries.currentDetails(company,created.employee().id());
        assertThat(a.asOf()).isEqualTo(today);assertThat(a.assignment()).isPresent();
        assertThat(a.assignment().get().assignmentId()).isEqualTo(created.assignmentId());
        assertThat(a.assignment().get().effectiveTo()).isNull();
        assertThat(jdbc.queryForObject("select created_at=updated_at from hr_employee where id=?",Boolean.class,created.employee().id())).isTrue();
        verify(clock,times(2)).instant();
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void t02_failureAtEmployeeFlushAssignmentPersistOrAssignmentFlushRollsBack(int point) {
        if(point==2) doThrow(new IllegalStateException("before assignment insert")).when(store).add(any(EmployeeAssignment.class));
        else {
            AtomicInteger n=new AtomicInteger();doAnswer(call->{call.callRealMethod();if(n.incrementAndGet()==(point==1?1:2))throw new IllegalStateException("after real flush");return null;}).when(store).flush();
        }
        assertThatThrownBy(()->create("FAIL")).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalStateException.class);pairCount(0);
        reset(store);create("FAIL");pairCount(1);
    }
    @Test void t03_concurrentCanonicalEmployeeNoCreatesOnePair() {
        var reached=new CountDownLatch(2);var go=gate();
        doAnswer(c->{reached.countDown();await(go);return c.callRealMethod();}).when(store).add(any(Employee.class));
        var a=job(()->create(" e-01 "));var b=job(()->create("E-01"));await(reached);go.countDown();
        var results=List.of(result(a),result(b));
        assertThat(results.stream().filter(EmployeeCreated.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(HrFailure.class::isInstance).map(HrFailure.class::cast).map(HrFailure::code)).containsExactly(EMPLOYEE_NO_CONFLICT);
        pairCount(1);
    }
    @Test void t04_dbTextChecksUnicodeBoundariesAndDuplicateNames() {
        registration.createEmployee(new CreateEmployee(company,"A".repeat(32),"😀".repeat(100),today,site,department,position,"😀".repeat(500)));
        var e=create("B");create("C");pairCount(3);
        sqlFailure("23514","ck_hr_employee_name",()->jdbc.update("update hr_employee set display_name=? where id=?","\u00a0",e.employee().id()));
        sqlFailure("23514","ck_hr_employee_name",()->jdbc.update("update hr_employee set display_name=? where id=?","bad\u0085",e.employee().id()));
        sqlFailure("23514","ck_hr_assignment_reason",()->jdbc.update("update hr_employee_assignment set reason=? where id=?","bad\t",e.assignmentId()));
        sqlFailure("23514","ck_hr_employee_no",()->jdbc.update("update hr_employee set employee_no='lower' where id=?",e.employee().id()));
        sqlFailure("22001",null,()->jdbc.update("update hr_employee set display_name=? where id=?","😀".repeat(101),e.employee().id()));
    }
    @Test void t05_missingWrongCompanyAndWrongKindDoNotInsert() {
        for(var request:List.of(new CreateEmployee(UUID.randomUUID(),"A","name",today,site,department,position,"r"),
                new CreateEmployee(company,"A","name",today,UUID.randomUUID(),department,position,"r"),
                new CreateEmployee(company,"A","name",today,department,department,position,"r")))
            assertThatThrownBy(()->registration.createEmployee(request)).isInstanceOf(OrganizationFailure.class);
        failure(INVALID_REFERENCE,()->registration.createEmployee(new CreateEmployee(company,"A","name",today,null,department,position,"r")));
        pairCount(0);
    }
    @Test void t06_departmentAndPositionMayBeUsedAcrossSites() {
        var first=create("A");var other=organization.registerSite("S2","다른 사업장").siteId();
        var second=registration.createEmployee(new CreateEmployee(company,"B","same name",today,other,department,position,"new"));
        assertThat(queries.currentDetails(company,first.employee().id()).assignment().orElseThrow().siteId()).isEqualTo(site);
        assertThat(queries.currentDetails(company,second.employee().id()).assignment().orElseThrow().siteId()).isEqualTo(other);pairCount(2);
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t07_t22_inactiveTargetsRejectNewAndRetryWithoutDeletingHistory(OrganizationTarget kind) {
        var first=create("A");deactivate(kind);
        assertThatThrownBy(()->create("B")).isInstanceOf(OrganizationFailure.class);
        assertThatThrownBy(()->create(" a ")).isInstanceOf(OrganizationFailure.class);
        pairCount(1);assertThat(queries.currentDetails(company,first.employee().id()).assignment()).isPresent();
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t08_guardFirstBlocksDeactivationUntilActualHrCommit(OrganizationTarget kind) {
        var reached=gate();var go=gate();
        doAnswer(c->{c.callRealMethod();reached.countDown();await(go);return null;}).when(store).add(any(EmployeeAssignment.class));
        var a=job(()->create("A"));await(reached);var b=job(()->deactivate(kind));
        blocked("for update");go.countDown();
        assertThat(result(a)).isInstanceOf(EmployeeCreated.class);assertThat(result(b)).isInstanceOf(OrganizationStateResult.class);pairCount(1);
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t08_deactivateFirstCommitRejectsWaitingHr(OrganizationTarget kind) {
        var reached=gate();var go=gate();
        doAnswer(c->{var value=c.callRealMethod();reached.countDown();await(go);return value;})
                .when(locks).lockForState(eq(kind),eq(company),eq(target(kind)));
        var a=job(()->deactivate(kind));await(reached);var b=job(()->create("A"));
        blocked("for share");go.countDown();assertThat(result(a)).isInstanceOf(OrganizationStateResult.class);
        assertThat(result(b)).isInstanceOf(OrganizationFailure.class);pairCount(0);
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t08_deactivateFirstRollbackLetsWaitingHrCommit(OrganizationTarget kind) {
        var reached=gate();var go=gate();
        doAnswer(c->{c.callRealMethod();reached.countDown();await(go);throw new IllegalStateException("abort organization root");})
                .when(locks).lockForState(eq(kind),eq(company),eq(target(kind)));
        var a=job(()->deactivate(kind));await(reached);var b=job(()->create("A"));
        blocked("for share");go.countDown();assertThat(result(a)).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class);
        assertThat(result(b)).isInstanceOf(EmployeeCreated.class);pairCount(1);
    }
    Map<String,Object> physical() {return em.unwrap(Session.class).doReturningWork(c->{try(var s=c.createStatement();var r=s.executeQuery("select pg_backend_pid(),pg_current_xact_id()::text")){r.next();return Map.of("pid",r.getInt(1),"xid",r.getString(2));}});}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void t09_samePhysicalRootGuardWritesAndUnrelatedManagedProbe(boolean abort) {
        UUID probe=UUID.randomUUID();jdbc.update("insert into org03_probe.org03_test_probe values(?,'before')",probe);
        var ids=new ArrayList<Map<String,Object>>();var order=new ArrayList<OrganizationTarget>();
        var managed=new AtomicReference<ManagedProbe>();
        doAnswer(c->{managed.set(em.find(ManagedProbe.class,probe));ids.add(physical());c.callRealMethod();return null;}).when(locks).requireWritableTransaction();
        doAnswer(c->{order.add(c.getArgument(0));ids.add(physical());return c.callRealMethod();}).when(locks).lockForAssignment(any(),any(),any());
        doAnswer(c->{assertThat(em.contains(managed.get())).isTrue();assertThat(order).containsExactly(OrganizationTarget.SITE,OrganizationTarget.DEPARTMENT,OrganizationTarget.POSITION);
            ids.add(physical());managed.get().label("after");return c.callRealMethod();}).when(store).add(any(Employee.class));
        doAnswer(c->{ids.add(physical());c.callRealMethod();if(abort)throw new IllegalStateException("abort");return null;}).when(store).add(any(EmployeeAssignment.class));
        if(abort) assertThatThrownBy(()->create("A")).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalStateException.class);else create("A");
        assertThat(ids).hasSize(6).allMatch(ids.getFirst()::equals);
        assertThat(jdbc.queryForObject("select label from org03_probe.org03_test_probe where id=?",String.class,probe)).isEqualTo(abort?"before":"after");pairCount(abort?0:1);
        System.out.println("H12-EVIDENCE same physical HR/Guard/write transaction "+ids+" rollback="+abort+"; managed probe preserved");
        if(!abort){String logs=postgres.getLogs();String pid=ids.getFirst().get("pid").toString(),xid=ids.getFirst().get("xid").toString();
            var emitted=logs.lines().filter(l->l.contains(pid+" "+xid+" ")).toList();
            String trace=String.join("\n",emitted).toLowerCase(Locale.ROOT);
            assertThat(trace.indexOf("for share")).isLessThan(trace.indexOf("insert into hr_employee"));
            assertThat(trace).contains("for share","insert into hr_employee_assignment");
        }
    }
    @Test void t10_ambientRootRejectedAndCaughtGuardFailurePoisonsRoot() {
        tx(()->{failure(TRANSACTION_BOUNDARY_VIOLATION,()->create("A"));return null;});pairCount(0);
        deactivate(OrganizationTarget.SITE);
        assertThatThrownBy(()->tx(()->{
            try{guard.requireAssignable(new OrganizationAssignmentTargets(company,site,department,position));}catch(OrganizationFailure ignored){}
            jdbc.update("update organization_company set name='must rollback' where id=?",company);return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(organization.getCompany().name()).isNotEqualTo("must rollback");pairCount(0);
    }
    @Test void t11_overlapInsertUpdateAdjacencyAndOtherEmployee() {
        var a=create("A");close(a.assignmentId(),today.plusDays(5));var next=fixtureAssignment(a.employee().id(),today.plusDays(5),null);
        sqlFailure("23P01","ex_hr_assignment_employee_period",()->fixtureAssignment(a.employee().id(),today.plusDays(1),today.plusDays(2)));
        sqlFailure("23P01","ex_hr_assignment_employee_period",()->fixtureAssignment(a.employee().id(),today,null));
        sqlFailure("23P01","ex_hr_assignment_employee_period",()->jdbc.update("update hr_employee_assignment set effective_from=? where id=?",today.plusDays(4),next));
        assertThat(jdbc.queryForObject("select effective_from from hr_employee_assignment where id=?",LocalDate.class,next)).isEqualTo(today.plusDays(5));
        create("B");assertThat(queries.history(company,a.employee().id(),0,10).items()).hasSize(2);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void t11_excludeWaitSameCommittedEmployeeCommitVersusRollback(boolean commit) {
        var e=create("A");close(e.assignmentId(),today.plusDays(1));var held=gate();var go=gate();
        var a=job(()->new TransactionTemplate(manager).execute(s->{fixtureAssignment(e.employee().id(),today.plusDays(1),null);held.countDown();await(go);if(!commit)s.setRollbackOnly();return "a done";}));
        await(held);var b=job(()->tx(()->fixtureAssignment(e.employee().id(),today.plusDays(2),null)));
        blocked("insert into hr_employee_assignment");go.countDown();assertThat(result(a)).isEqualTo("a done");var outcome=result(b);
        if(commit){var p=pg((Throwable)outcome);assertThat(p.getSQLState()).isEqualTo("23P01");assertThat(p.getServerErrorMessage().getConstraint()).isEqualTo("ex_hr_assignment_employee_period");}
        else assertThat(outcome).isInstanceOf(UUID.class);
        assertThat(jdbc.queryForObject("select count(*) from hr_employee_assignment where employee_id=?",Integer.class,e.employee().id())).isEqualTo(2);
        System.out.println("H12-EVIDENCE EXCLUDE waiter on committed Employee: blocker "+(commit?"COMMIT -> 23P01":"ROLLBACK -> waiting INSERT committed"));
    }
    @Test void t12_dbDateNullAndInfinityChecks() {
        var e=create("A");
        for(String date:List.of("'infinity'::date","'-infinity'::date","'0001-01-01 BC'::date")) {
            sqlFailure("23514","ck_hr_assignment_period",()->jdbc.update("update hr_employee_assignment set effective_from="+date+" where id=?",e.assignmentId()));
            sqlFailure("23514","ck_hr_employee_employment_dates",()->jdbc.update("update hr_employee set joined_on="+date+" where id=?",e.employee().id()));
        }
        for(String date:List.of("'infinity'::date","'-infinity'::date","'2026-10-26'::date","'2026-10-25'::date"))
            sqlFailure("23514","ck_hr_assignment_period",()->jdbc.update("update hr_employee_assignment set effective_to="+date+" where id=?",e.assignmentId()));
        sqlFailure("23502",null,()->jdbc.update("update hr_employee_assignment set effective_from=null where id=?",e.assignmentId()));
        sqlFailure("23514","ck_hr_employee_employment_dates",()->jdbc.update("update hr_employee set employment_status='TERMINATED' where id=?",e.employee().id()));
        sqlFailure("23514","ck_hr_employee_employment_dates",()->jdbc.update("update hr_employee set termination_effective_on=? where id=?",today.plusDays(1),e.employee().id()));
    }
    @Test void t13_t14_halfOpenDateBoundariesAndLocalVersusFullTimelineChecks() {
        var e=create("A");close(e.assignmentId(),today.plusDays(10));var next=fixtureAssignment(e.employee().id(),today.plusDays(10),null);
        assertThat(queries.findAssignmentAt(company,e.employee().id(),today.plusDays(9)).orElseThrow().assignmentId()).isEqualTo(e.assignmentId());
        assertThat(queries.findAssignmentAt(company,e.employee().id(),today.plusDays(10)).orElseThrow().assignmentId()).isEqualTo(next);
        assertThat(queries.currentDetails(company,e.employee().id()).assignment().orElseThrow().effectiveTo()).isEqualTo(today.plusDays(10));
        assertThat(queries.findAssignmentAt(company,e.employee().id(),today.minusDays(1))).isEmpty();
        close(next,today.plusDays(20));jdbc.update("update hr_employee set employment_status='TERMINATED',termination_effective_on=? where id=?",today.plusDays(20),e.employee().id());
        assertThat(queries.findAssignmentAt(company,e.employee().id(),today.plusDays(20))).isEmpty();
        // Raw SQL gaps are a documented limit; a valid date lookup does not attest the whole timeline.
        jdbc.update("update hr_employee_assignment set effective_from=? where id=?",today.plusDays(11),next);
        failure(HR_ASSIGNMENT_INTEGRITY_VIOLATION,()->queries.findAssignmentAt(company,e.employee().id(),today.plusDays(10)));
        assertThat(queries.findAssignmentAt(company,e.employee().id(),today)).isPresent();
        jdbc.update("update hr_employee_assignment set effective_from=? where id=?",today.minusDays(1),e.assignmentId());
        failure(HR_ASSIGNMENT_INTEGRITY_VIOLATION,()->queries.findAssignmentAt(company,e.employee().id(),today));
        jdbc.update("update hr_employee_assignment set effective_to=null where id=?",next);
        failure(HR_ASSIGNMENT_INTEGRITY_VIOLATION,()->queries.findAssignmentAt(company,e.employee().id(),today.plusDays(12)));
        failure(HR_ASSIGNMENT_INTEGRITY_VIOLATION,()->queries.findAssignmentAt(company,e.employee().id(),today.plusDays(20)));
    }
    @Test void t15_scalarProjectionIgnoresStaleManagedEmployeeAndKeepsItManaged() {
        var e=create("A");var otherSite=organization.registerSite("S2","second").siteId();
        tx(()->{
            var stale=em.find(Employee.class,e.employee().id());assertThat(stale.identity().version()).isZero();
            assertThat(result(job(()->tx(()->{
                jdbc.update("update hr_employee set display_name='new snapshot',version=1 where id=?",e.employee().id());
                jdbc.update("update hr_employee_assignment set site_id=?,version=1 where id=?",otherSite,e.assignmentId());return "updated";
            })))).isEqualTo("updated");
            var fresh=queries.findAssignmentAt(company,e.employee().id(),today).orElseThrow();
            assertThat(fresh.employee().version()).isEqualTo(1);assertThat(fresh.employee().displayName()).isEqualTo("new snapshot");
            assertThat(fresh.siteId()).isEqualTo(otherSite);assertThat(stale.identity().version()).isZero();assertThat(em.contains(stale)).isTrue();
            return null;
        });
        assertThat(postgres.getLogs()).contains("left join hr_employee_assignment");
        System.out.println("H12-EVIDENCE stale managed Employee v0 retained; single HR SQL scalar projection returned Employee v1 + new Assignment target");
    }
    @Test void t16_inactiveAndRenamedOrganizationDoesNotChangeExistingAssignmentAndDeleteIsRestricted() {
        var e=create("A");deactivate(OrganizationTarget.DEPARTMENT);organization.renameDepartment(company,department,"renamed",1L);
        assertThat(queries.history(company,e.employee().id(),0,10).items().getFirst().departmentId()).isEqualTo(department);
        assertThat(organization.findDepartment(company,department).orElseThrow().name()).isEqualTo("renamed");
        for(String table:List.of("organization_site","organization_department","organization_position","hr_employee"))
            sqlFailure("23503",null,()->jdbc.update("delete from "+table));
    }
    @Test void t17_missingEmployeeZeroAndMultipleCandidatesAreDistinct() {
        assertThat(queries.findEmployee(company,UUID.randomUUID())).isEmpty();
        failure(EMPLOYEE_NOT_FOUND,()->queries.findAssignmentAt(company,UUID.randomUUID(),today));
        var e=create("A");jdbc.update("delete from hr_employee_assignment where id=?",e.assignmentId());
        assertThat(queries.findEmployee(company,e.employee().id())).isPresent();failure(HR_ASSIGNMENT_INTEGRITY_VIOLATION,()->queries.findAssignmentAt(company,e.employee().id(),today));
        fixtureAssignment(e.employee().id(),today,null);
        new TransactionTemplate(manager).executeWithoutResult(s->{
            jdbc.execute("alter table hr_employee_assignment drop constraint ex_hr_assignment_employee_period");
            fixtureAssignment(e.employee().id(),today,null);
            failure(HR_ASSIGNMENT_INTEGRITY_VIOLATION,()->queries.findAssignmentAt(company,e.employee().id(),today));s.setRollbackOnly();
        });
        assertThat(queries.findAssignmentAt(company,e.employee().id(),today)).isPresent();
        sqlFailure("23P01","ex_hr_assignment_employee_period",()->fixtureAssignment(e.employee().id(),today,null));
    }
    @Test void t17_companyEmptyMultipleAndWrongCompanyPropagateOrganizationFailures() {
        assertThatThrownBy(()->queries.findEmployee(UUID.randomUUID(),UUID.randomUUID())).isInstanceOf(OrganizationFailure.class);
        new TransactionTemplate(manager).executeWithoutResult(s->{
            jdbc.execute("delete from organization_site");jdbc.execute("delete from organization_department");jdbc.execute("delete from organization_position");jdbc.execute("delete from organization_company");
            assertThatThrownBy(()->queries.findEmployee(company,UUID.randomUUID())).isInstanceOfSatisfying(OrganizationFailure.class,e->assertThat(e.code()).isEqualTo(OrganizationFailure.Code.COMPANY_CONFIGURATION_INVALID));s.setRollbackOnly();
        });
    }
    @Test void t18_actualConstraintNamesTranslateOnlyKnownPairs() {
        var e=create("A");failure(EMPLOYEE_NO_CONFLICT,()->create(" a "));
        var p=pg(catchThrowable(()->fixtureAssignment(e.employee().id(),today,null)));
        var failure=new ConstraintViolationException("actual PG error",p,p.getServerErrorMessage().getConstraint());
        assertThat(store.translate(failure)).isInstanceOfSatisfying(HrFailure.class,f->assertThat(f.code()).isEqualTo(ASSIGNMENT_PERIOD_CONFLICT));
        var fk=pg(catchThrowable(()->jdbc.update("update hr_employee_assignment set site_id=? where id=?",UUID.randomUUID(),e.assignmentId())));
        var technical=new ConstraintViolationException("actual PG FK",fk,fk.getServerErrorMessage().getConstraint());assertThat(store.translate(technical)).isSameAs(technical);
    }
    @Test void t23_deferredDatabaseCommitFailureRollsBackPair() {
        jdbc.execute("create function hr_test_abort_commit() returns trigger language plpgsql as $$ begin raise exception 'test commit abort' using errcode='23514'; return null; end $$");
        jdbc.execute("create constraint trigger hr_test_abort after insert on hr_employee_assignment deferrable initially deferred for each row execute function hr_test_abort_commit()");
        try{var failure=catchThrowable(()->create("A"));assertThat(pg(failure).getSQLState()).isEqualTo("23514");assertThat(failure).isNotInstanceOf(HrFailure.class);pairCount(0);}
        finally{jdbc.execute("drop trigger hr_test_abort on hr_employee_assignment");jdbc.execute("drop function hr_test_abort_commit()");}
        create("A");pairCount(1);
    }
    @Test void t22_t23_realCommitThenLostAckWrapperDoesNotRetryOrRollbackAgain() {
        var commits=new AtomicInteger();var rollbacks=new AtomicInteger();
        PlatformTransactionManager lostAck=new PlatformTransactionManager(){
            public TransactionStatus getTransaction(TransactionDefinition d){return manager.getTransaction(d);}
            public void commit(TransactionStatus s){manager.commit(s);commits.incrementAndGet();throw new TransactionSystemException("test ACK lost AFTER real commit");}
            public void rollback(TransactionStatus s){rollbacks.incrementAndGet();manager.rollback(s);}
        };
        var service=new EmployeeRegistrationService(store,guard,lostAck,clock);
        assertThatThrownBy(()->service.createEmployee(input("A"))).isInstanceOf(TransactionSystemException.class);
        assertThat(commits.get()).isEqualTo(1);assertThat(rollbacks.get()).isZero();pairCount(1);
        failure(EMPLOYEE_NO_CONFLICT,()->create(" a "));pairCount(1);
        System.out.println("H12-EVIDENCE ACK wrapper after actual COMMIT: pair retained, no retry/second rollback; not a real network-loss test");
    }
    @Test void t23_excludeWaitTimeoutIsTechnicalAndLeavesNoNewRow() {
        var e=create("A");close(e.assignmentId(),today.plusDays(1));var held=gate();var go=gate();
        var a=job(()->tx(()->{fixtureAssignment(e.employee().id(),today.plusDays(1),null);held.countDown();await(go);return "done";}));
        await(held);
        var failure=catchThrowable(()->tx(()->{jdbc.execute("set local lock_timeout='150ms'");return fixtureAssignment(e.employee().id(),today.plusDays(2),null);}));
        assertThat(pg(failure).getSQLState()).isEqualTo("55P03");assertThat(store.translate((RuntimeException)failure)).isSameAs(failure);
        go.countDown();assertThat(result(a)).isEqualTo("done");assertThat(jdbc.queryForObject("select count(*) from hr_employee_assignment",Integer.class)).isEqualTo(2);
    }
    @Test void t27_pagingUsesCanonicalNumberAndHistoryDateOrder() {
        for(String no:List.of("C","A","B"))create(no);
        var first=queries.employees(company,null,0,2);assertThat(first.items()).extracting(EmployeeIdentity::employeeNo).containsExactly("A","B");assertThat(first.hasNext()).isTrue();
        var second=queries.employees(company,"EMPLOYED",1,2);assertThat(second.items()).extracting(EmployeeIdentity::employeeNo).containsExactly("C");assertThat(second.hasNext()).isFalse();
        assertThat(queries.employees(company,"ON_LEAVE",0,null).items()).isEmpty();
        var employee=first.items().getFirst().id();var a=queries.currentDetails(company,employee).assignment().orElseThrow();close(a.assignmentId(),today.plusDays(1));var next=fixtureAssignment(employee,today.plusDays(1),null);
        var h=queries.history(company,employee,0,1);assertThat(h.items().getFirst().id()).isEqualTo(next);assertThat(h.hasNext()).isTrue();
        assertThat(queries.history(company,employee,1,1).items().getFirst().id()).isEqualTo(a.assignmentId());
    }
    @Test void t27_t30_oneRootKeepsDateAcrossMidnightButNewRetryGetsNewDate() {
        var reached=gate();var go=gate();
        doAnswer(c->{c.callRealMethod();reached.countDown();await(go);return null;}).when(locks).requireWritableTransaction();
        var a=job(()->create("A"));await(reached);when(clock.instant()).thenReturn(instant.plus(Duration.ofDays(1)));go.countDown();
        assertThat(result(a)).isInstanceOf(EmployeeCreated.class);verify(clock,times(1)).instant();
        failure(UNSUPPORTED_JOINED_ON,()->create("A"));pairCount(1);
        assertThat(queries.currentDetails(company,queries.employees(company,null,0,20).items().getFirst().id()).asOf()).isEqualTo(today.plusDays(1));
    }
    @Test void t30_pastAndFutureHireDatesAreRejected() {
        for(LocalDate date:List.of(today.minusDays(1),today.plusDays(1)))
            failure(UNSUPPORTED_JOINED_ON,()->registration.createEmployee(new CreateEmployee(company,"A","name",date,site,department,position,"r")));
        pairCount(0);
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t05_compositeFkRejectsOtherCompanyEvenWhenTargetIdExists(OrganizationTarget kind) {
        var e=create("A");
        new TransactionTemplate(manager).executeWithoutResult(s->{
            jdbc.execute("alter table organization_company drop constraint uq_organization_company_slot");
            UUID other=UUID.randomUUID(),foreign=UUID.randomUUID();
            jdbc.update("insert into organization_company values(?,1,'OTHER','other',0,now(),now())",other);
            String name=kind.name().toLowerCase(Locale.ROOT);
            jdbc.update("insert into organization_"+name+"(id,company_id,code,name,status,version,created_at,updated_at) values(?,?,'X','foreign','ACTIVE',0,now(),now())",foreign,other);
            assertThatThrownBy(()->queries.findEmployee(company,e.employee().id())).isInstanceOfSatisfying(OrganizationFailure.class,f->assertThat(f.code()).isEqualTo(OrganizationFailure.Code.COMPANY_CONFIGURATION_INVALID));
            sqlFailure("23503","fk_hr_assignment_"+name+"_company",()->jdbc.update("update hr_employee_assignment set "+name+"_id=? where id=?",foreign,e.assignmentId()));
            s.setRollbackOnly();
        });
        assertThat(queries.currentDetails(company,e.employee().id()).assignment()).isPresent();
        assertThat(jdbc.queryForObject("select count(*) from organization_company",Integer.class)).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t08_hrRollbackReleasesGuardAndStateChangeCanCommit(OrganizationTarget kind) {
        var reached=gate();var go=gate();
        doAnswer(c->{c.callRealMethod();reached.countDown();await(go);throw new IllegalStateException("HR root abort");}).when(store).add(any(EmployeeAssignment.class));
        var a=job(()->create("A"));await(reached);var b=job(()->deactivate(kind));blocked("for update");go.countDown();
        assertThat(result(a)).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class);assertThat(result(b)).isInstanceOf(OrganizationStateResult.class);pairCount(0);
    }
    @Test void t15_concurrentAtomicFixtureWriterNeverProducesMixedEmployeeAssignmentSnapshot() {
        var e=create("A");var other=organization.registerSite("S2","second").siteId();var start=gate();
        var writer=job(()->{await(start);for(int i=1;i<=30;i++){int n=i;tx(()->{
            jdbc.update("update hr_employee set version=? where id=?",n,e.employee().id());
            jdbc.update("update hr_employee_assignment set site_id=? where id=?",n%2==0?site:other,e.assignmentId());return null;
        });}return "done";});
        start.countDown();for(int i=0;i<60;i++) {
            var facts=queries.findAssignmentAt(company,e.employee().id(),today).orElseThrow();
            assertThat(facts.siteId()).isEqualTo(facts.employee().version()%2==0?site:other);
        }
        assertThat(result(writer)).isEqualTo("done");
    }
    @Test void t23_excludeDeadlockIsTechnicalNotPeriodConflict() {
        var e=create("A");var other=create("B");close(e.assignmentId(),today.plusDays(1));close(other.assignmentId(),today.plusDays(1));
        var ready=new CountDownLatch(2);var go=gate();
        Supplier<Object> first=()->tx(()->{jdbc.execute("set local deadlock_timeout='100ms'");fixtureAssignment(e.employee().id(),today.plusDays(1),null);ready.countDown();await(go);fixtureAssignment(other.employee().id(),today.plusDays(1),null);return "committed";});
        Supplier<Object> second=()->tx(()->{jdbc.execute("set local deadlock_timeout='100ms'");fixtureAssignment(other.employee().id(),today.plusDays(1),null);ready.countDown();await(go);fixtureAssignment(e.employee().id(),today.plusDays(1),null);return "committed";});
        var a=job(first);var b=job(second);await(ready);go.countDown();var outcomes=List.of(result(a),result(b));
        assertThat(outcomes.stream().filter("committed"::equals)).hasSize(1);
        var failed=(RuntimeException)outcomes.stream().filter(RuntimeException.class::isInstance).findFirst().orElseThrow();
        assertThat(pg(failed).getSQLState()).isEqualTo("40P01");assertThat(store.translate(failed)).isSameAs(failed);
        assertThat(jdbc.queryForObject("select count(*) from hr_employee_assignment",Integer.class)).isEqualTo(4);
        System.out.println("H12-EVIDENCE actual EXCLUDE deadlock SQLSTATE 40P01 remains technical; victim transaction rolled back");
    }
    @Test void t23_connectionTerminationBeforeCommitDoesNotLeavePartialPair() {
        doAnswer(c->{c.callRealMethod();int pid=(int)physical().get("pid");
            assertThat(result(job(()->jdbc.queryForObject("select pg_terminate_backend(?)",Boolean.class,pid)))).isEqualTo(true);return null;
        }).when(store).add(any(EmployeeAssignment.class));
        var failure=catchThrowable(()->create("A"));assertThat(failure).isNotNull().isNotInstanceOf(HrFailure.class);pairCount(0);
    }
    @Test void t18_rootCompletionTranslatesNamedConstraintAfterRealDatabaseFailure() {
        jdbc.execute("create function hr_test_unique_commit() returns trigger language plpgsql as $$ begin raise exception 'test unique at commit' using errcode='23505',constraint='uq_hr_employee_company_employee_no'; return null; end $$");
        jdbc.execute("create constraint trigger hr_test_unique after insert on hr_employee_assignment deferrable initially deferred for each row execute function hr_test_unique_commit()");
        try{failure(EMPLOYEE_NO_CONFLICT,()->create("A"));pairCount(0);}
        finally{jdbc.execute("drop trigger hr_test_unique on hr_employee_assignment");jdbc.execute("drop function hr_test_unique_commit()");}
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void t10_wrongManagerOrReadOnlyRootCannotBypassOrganizationGuard(boolean readOnly) {
        PlatformTransactionManager incorrect;
        if(readOnly) incorrect=new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                var d=new DefaultTransactionDefinition(definition);d.setReadOnly(true);return manager.getTransaction(d);
            }
            public void commit(TransactionStatus status){manager.commit(status);}
            public void rollback(TransactionStatus status){manager.rollback(status);}
        };
        else incorrect=new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource());
        var service=new EmployeeRegistrationService(store,guard,incorrect,clock);
        assertThatThrownBy(()->service.createEmployee(input("A"))).isInstanceOfSatisfying(OrganizationFailure.class,
                f->assertThat(f.code()).isEqualTo(OrganizationFailure.Code.TRANSACTION_BOUNDARY_VIOLATION));
        pairCount(0);verify(store,never()).add(any(Employee.class));verify(store,never()).add(any(EmployeeAssignment.class));
    }
    @Test void t14_t17_onLeaveIsAnEmploymentFactAndQueryDatabaseFailureIsNotEmpty() {
        var e=create("A");jdbc.update("update hr_employee set employment_status='ON_LEAVE' where id=?",e.employee().id());
        assertThat(queries.findAssignmentAt(company,e.employee().id(),today).orElseThrow().employee().employmentStatus()).isEqualTo("ON_LEAVE");
        doAnswer(c->{int pid=(int)physical().get("pid");
            assertThat(result(job(()->jdbc.queryForObject("select pg_terminate_backend(?)",Boolean.class,pid)))).isEqualTo(true);
            return c.callRealMethod();
        }).when(store).atDate(any(),any(),any());
        assertThatThrownBy(()->queries.findAssignmentAt(company,e.employee().id(),today)).isNotInstanceOf(HrFailure.class).isInstanceOf(RuntimeException.class);
    }

}
