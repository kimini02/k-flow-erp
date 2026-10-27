package com.kflow.erp.hr;

import com.kflow.erp.KFlowErpApplication;
import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.application.*;
import com.kflow.erp.hr.domain.*;
import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.application.*;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
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
@Import(EmployeeAssignmentChangeIT.ProbeConfiguration.class)
@Timeout(60)
class EmployeeAssignmentChangeIT {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16")
            .withInitScript("org03-probe.sql").withCommand("postgres","-c","log_statement=all","-c","log_line_prefix=%p %x ");
    @TestConfiguration @EntityScan(basePackageClasses={KFlowErpApplication.class,ManagedProbe.class})
    static class ProbeConfiguration {}
    @Autowired EmployeeAssignmentService service;
    @Autowired EmployeeRegistrationService registration;
    @Autowired EmployeeQueryService queries;
    @Autowired OrganizationService organization;
    @Autowired OrganizationStateService states;
    @Autowired OrganizationAssignmentGuard guard;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoSpyBean HrStore store;
    @MockitoSpyBean HrAssignmentStore assignments;
    @MockitoSpyBean OrganizationLockStore locks;
    @MockitoBean(name="hrClock") Clock clock;
    final LocalDate joined=LocalDate.of(2026,10,1), today=LocalDate.of(2026,10,11);
    final Instant instant=Instant.parse("2026-10-11T03:00:00Z");
    UUID company,site,department,position,site2,department2,position2;
    ExecutorService pool;
    List<CountDownLatch> gates=new ArrayList<>();
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(instant);
        jdbc.update("delete from hr_employee_assignment"); jdbc.update("delete from hr_employee");
        jdbc.update("delete from org03_probe.org03_test_probe");
        for(String t:List.of("site","department","position")) jdbc.update("delete from organization_"+t);
        company=organization.getCompany().companyId();
        site=organization.registerSite("S","사업장").siteId(); site2=organization.registerSite("S2","다른 사업장").siteId();
        department=organization.registerDepartment("D","개발팀").departmentId(); department2=organization.registerDepartment("D2","운영팀").departmentId();
        position=organization.registerPosition("P","사원").positionId(); position2=organization.registerPosition("P2","대리").positionId();
        pool=Executors.newFixedThreadPool(4);
    }
    @AfterEach void cleanup() throws Exception {
        gates.forEach(CountDownLatch::countDown); pool.shutdownNow(); assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();
    }
    EmployeeCreated create(String no) {
        // Real registration under its OWN fixed Clock. No shared Clock mutation or production backdated input.
        var registrar=new EmployeeRegistrationService(store,guard,manager,Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"),ZoneOffset.UTC));
        return registrar.createEmployee(new CreateEmployee(company,no,"가상 직원",joined,site,department,position,"최초 근거"));
    }
    ChangeAssignment input(EmployeeCreated e) { return request(e.employee().id(),0L,today,site2,department2,position2,"  전보 근거  "); }
    ChangeAssignment request(UUID e,Long v,LocalDate d,UUID s,UUID dep,UUID p,String r) {
        return new ChangeAssignment(company,e,v,d,s,dep,p,r);
    }
    void failure(HrFailure.Code code,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(HrFailure.class,e->assertThat(e.code()).isEqualTo(code));
    }
    <T> T tx(Supplier<T> action) {return new TransactionTemplate(manager).execute(s->action.get());}
    CountDownLatch gate(){var g=new CountDownLatch(1);gates.add(g);return g;}
    void await(CountDownLatch g) {try{assertThat(g.await(12,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    Future<Object> job(Supplier<?> action) {return pool.submit(()->{try{return action.get();}catch(RuntimeException e){return e;}});}
    Object result(Future<Object> f) {try{return f.get(15,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}}
    Map<String,Object> physical() {return em.unwrap(Session.class).doReturningWork(c->{try(var s=c.createStatement();var r=s.executeQuery("select pg_backend_pid(),pg_current_xact_id()::text")){r.next();return Map.of("pid",r.getInt(1),"xid",r.getString(2));}});}
    void blocked(String query) {blocked(query,null,null);}
    void blocked(String query,Integer blocker,Integer waiting) {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
        while(System.nanoTime()<until) {
            var rows=jdbc.queryForList("select pid,pg_blocking_pids(pid)::text blockers,query from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and cardinality(pg_blocking_pids(pid))>0 and query ilike ?", "%"+query+"%");
            for(var r:rows) if((waiting==null||waiting.equals(r.get("pid")))&&(blocker==null||Arrays.asList(r.get("blockers").toString().replace("{","").replace("}","").split(",")).contains(blocker.toString()))) {
                System.out.println("H34-EVIDENCE actual PG blocker: "+r);return;
            }
            try{Thread.sleep(15);}catch(InterruptedException e){throw new AssertionError(e);}
        }
        fail("No PostgreSQL blocking observed: "+query);
    }
    Map<String,Object> employee(UUID e) {return jdbc.queryForMap("select * from hr_employee where id=?",e);}
    List<Map<String,Object>> history(UUID e) {return jdbc.queryForList("select * from hr_employee_assignment where employee_id=? order by effective_from,id",e);}
    void unchanged(EmployeeCreated e,Map<String,Object> before,List<Map<String,Object>> rows) {
        assertThat(employee(e.employee().id())).isEqualTo(before);assertThat(history(e.employee().id())).isEqualTo(rows);
    }
    PSQLException pg(Throwable failure) {
        for(Throwable c=failure;c!=null;c=c.getCause()) if(c instanceof PSQLException p)return p;
        throw new AssertionError("Expected actual PostgreSQL error",failure);
    }
    void sqlFailure(String state,String constraint,Runnable action) {
        var p=pg(catchThrowable(action::run));assertThat(p.getSQLState()).isEqualTo(state);
        if(constraint!=null) assertThat(p.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
    }
    UUID fixtureAssignment(UUID e,LocalDate from,LocalDate to) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into hr_employee_assignment(id,company_id,employee_id,site_id,department_id,position_id,effective_from,effective_to,reason,version,created_at,updated_at) values(?,?,?,?,?,?,?,?,'DB-only fixture',0,now(),now())",
            id,company,e,site,department,position,from,to);return id;
    }
    void close(UUID a,LocalDate date) {jdbc.update("update hr_employee_assignment set effective_to=? where id=?",date,a);}
    UUID target(OrganizationTarget kind) {return switch(kind){case SITE->site;case DEPARTMENT->department;case POSITION->position;};}
    OrganizationStateResult deactivate(OrganizationTarget kind) {
        return switch(kind){case SITE->states.deactivateSite(company,site,0L,"중지");case DEPARTMENT->states.deactivateDepartment(company,department,0L,"중지");case POSITION->states.deactivatePosition(company,position,0L,"중지");};
    }
    ChangeAssignment retainingTarget(EmployeeCreated e,OrganizationTarget kind) {
        return request(e.employee().id(),0L,today,kind==OrganizationTarget.SITE?site:site2,
                kind==OrganizationTarget.DEPARTMENT?department:department2,kind==OrganizationTarget.POSITION?position:position2,"승진");
    }

    @ParameterizedTest @ValueSource(ints={1,2,3,4})
    void t01_changeOneOrAllDestinationsPreservesIdentityAndFullTimeline(int mode) {
        var e=create("A");var before=employee(e.employee().id());var initial=history(e.employee().id()).getFirst();
        UUID s=mode==1||mode==4?site2:site,d=mode==2||mode==4?department2:department,p=mode==3||mode==4?position2:position;
        var changed=service.changeAssignment(request(e.employee().id(),0L,today,s,d,p,"  전보 근거  "));
        assertThat(changed.employeeVersion()).isEqualTo(1);assertThat(changed.appliedOn()).isEqualTo(today);
        assertThat(changed.previous().id()).isEqualTo(e.assignmentId());assertThat(changed.previous().version()).isEqualTo(1);
        assertThat(changed.previous().reason()).isEqualTo("최초 근거");assertThat(changed.previous().effectiveTo()).isEqualTo(today);
        assertThat(changed.current().id().version()).isEqualTo(4);assertThat(changed.current().id()).isNotEqualTo(e.assignmentId());
        assertThat(changed.current().version()).isZero();assertThat(changed.current().reason()).isEqualTo("전보 근거");
        assertThat(changed.current().siteId()).isEqualTo(s);assertThat(changed.current().departmentId()).isEqualTo(d);assertThat(changed.current().positionId()).isEqualTo(p);
        var after=employee(e.employee().id());var expected=new HashMap<>(before);expected.put("version",1L);assertThat(after).isEqualTo(expected);
        var rows=history(e.employee().id());assertThat(rows).hasSize(2);
        var old=new HashMap<>(initial);old.put("version",1L);old.put("effective_to",java.sql.Date.valueOf(today));old.put("updated_at",java.sql.Timestamp.from(instant));assertThat(rows.getFirst()).isEqualTo(old);
        assertThat(queries.findAssignmentAt(company,e.employee().id(),today.minusDays(1)).orElseThrow().assignmentId()).isEqualTo(e.assignmentId());
        assertThat(queries.findAssignmentAt(company,e.employee().id(),today).orElseThrow().assignmentId()).isEqualTo(changed.current().id());
        verify(clock,times(1)).instant();
    }
    @Test void t06_t08_actualForceIncrementSqlExactlyOnceAndCloseBeforeInsert() {
        var e=create("A");var before=employee(e.employee().id());var physical=new AtomicReference<Map<String,Object>>();
        doAnswer(c->{physical.set(physical());c.callRealMethod();assertThat(employee(e.employee().id()).get("version")).isEqualTo(1L);return null;}).when(assignments).incrementEmployeeVersion(any());
        var result=service.changeAssignment(input(e));assertThat(result.employeeVersion()).isEqualTo(1);
        assertThat(employee(e.employee().id()).get("version")).isEqualTo(1L);assertThat(employee(e.employee().id()).get("updated_at")).isEqualTo(before.get("updated_at"));
        String key=physical.get().get("pid")+" "+physical.get().get("xid")+" ";
        String sql=postgres.getLogs().lines().filter(l->l.contains(key)).reduce("",(a,b)->a+b+"\n");
        var updates=sql.lines().filter(l->l.contains("update hr_employee set")).toList();
        assertThat(updates).hasSize(1);assertThat(updates.getFirst()).contains("version");
        assertThat(sql.indexOf("update hr_employee_assignment")).isLessThan(sql.indexOf("insert into hr_employee_assignment"));
        assertThat(sql).contains("FOR UPDATE","FOR SHARE");
        System.out.println("H34-EVIDENCE Hibernate="+org.hibernate.Version.getVersionString()+" DB/DTO Employee 0->1 exactly one version SQL; old assignment 0->1, new 0; updatedAt unchanged\n"+sql);
    }

    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t02_retainedInactiveDestinationStillRejectsPromotion(OrganizationTarget kind) {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());deactivate(kind);
        assertThatThrownBy(()->service.changeAssignment(retainingTarget(e,kind))).isInstanceOf(OrganizationFailure.class);
        unchanged(e,before,rows);assertThat(queries.findAssignmentAt(company,e.employee().id(),today)).isPresent();
    }
    @Test void t03_t04_unchangedSameDayAndStalePrecedence() {
        var e=create("A");
        failure(ASSIGNMENT_UNCHANGED,()->service.changeAssignment(request(e.employee().id(),0L,today,site,department,position,"r")));
        var changed=service.changeAssignment(input(e));
        var before=employee(e.employee().id());var rows=history(e.employee().id());
        failure(VERSION_CONFLICT,()->service.changeAssignment(input(e)));
        failure(ASSIGNMENT_UNCHANGED,()->service.changeAssignment(request(e.employee().id(),1L,today,site2,department2,position2,"r")));
        failure(SAME_DAY_ASSIGNMENT_CHANGE_UNSUPPORTED,()->service.changeAssignment(request(e.employee().id(),1L,today,site,department,position,"r")));
        failure(UNSUPPORTED_ASSIGNMENT_DATE,()->service.changeAssignment(request(e.employee().id(),0L,today.plusDays(1),site,department,position,"r")));
        deactivate(OrganizationTarget.SITE);
        assertThatThrownBy(()->service.changeAssignment(request(e.employee().id(),0L,today,site,department,position,"r"))).isInstanceOf(OrganizationFailure.class);
        unchanged(e,before,rows);assertThat(changed.employeeVersion()).isEqualTo(1);
    }
    @Test void t03_joiningDayDoesNotCreateEmptyPeriod() {
        var e=registration.createEmployee(new CreateEmployee(company,"A","fixture",today,site,department,position,"r"));
        var before=employee(e.employee().id());var rows=history(e.employee().id());
        failure(SAME_DAY_ASSIGNMENT_CHANGE_UNSUPPORTED,()->service.changeAssignment(input(e)));
        failure(ASSIGNMENT_UNCHANGED,()->service.changeAssignment(request(e.employee().id(),0L,today,site,department,position,"r")));
        unchanged(e,before,rows);
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void t07_rollbackAfterForceOldFlushOrNewFlush(int point) {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());
        if(point==1) doAnswer(c->{c.callRealMethod();throw new IllegalStateException("after actual force");}).when(assignments).incrementEmployeeVersion(any());
        else {var n=new AtomicInteger();doAnswer(c->{c.callRealMethod();if(n.incrementAndGet()==point-1)throw new IllegalStateException("after actual flush "+point);return null;}).when(store).flush();}
        assertThatThrownBy(()->service.changeAssignment(input(e))).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalStateException.class);
        unchanged(e,before,rows);
        System.out.println("H34-EVIDENCE rollback point="+point+" Employee version0 and updatedAt unchanged, old open version0/reason unchanged, row count1");
    }
    @ParameterizedTest @ValueSource(strings={"gap","first","outside","missing","multiple"})
    void t09_wholeTimelineDamageRejectedWithoutRepair(String kind) {
        var e=create("A");
        switch(kind) {
            case "gap" -> {close(e.assignmentId(),joined.plusDays(2));fixtureAssignment(e.employee().id(),joined.plusDays(3),null);}
            case "first" -> jdbc.update("update hr_employee_assignment set effective_from=? where id=?",joined.plusDays(1),e.assignmentId());
            case "outside" -> jdbc.update("update hr_employee_assignment set effective_from=? where id=?",joined.minusDays(1),e.assignmentId());
            case "missing" -> jdbc.update("delete from hr_employee_assignment where id=?",e.assignmentId());
            case "multiple" -> {
                jdbc.execute("alter table hr_employee_assignment drop constraint ex_hr_assignment_employee_period");
                fixtureAssignment(e.employee().id(),joined.plusDays(1),null);
            }
        }
        var before=employee(e.employee().id());var rows=history(e.employee().id());
        try {failure(HR_ASSIGNMENT_INTEGRITY_VIOLATION,()->service.changeAssignment(input(e)));unchanged(e,before,rows);}
        finally {if(kind.equals("multiple")) {
            jdbc.update("delete from hr_employee_assignment where id<>?",e.assignmentId());
            jdbc.execute("alter table hr_employee_assignment add constraint ex_hr_assignment_employee_period exclude using gist (employee_id with =, daterange(effective_from,effective_to,'[)') with &&) not deferrable");
        }}
    }
    @ParameterizedTest @ValueSource(strings={"ON_LEAVE","TERMINATED","FUTURE"})
    void t22_unsupportedStatesAndValidFutureTimeline(String kind) {
        var e=create("A");HrFailure.Code code;
        if(kind.equals("ON_LEAVE")) {jdbc.update("update hr_employee set employment_status='ON_LEAVE' where id=?",e.employee().id());code=EMPLOYMENT_STATE_UNSUPPORTED;}
        else if(kind.equals("TERMINATED")) {
            close(e.assignmentId(),today);jdbc.update("update hr_employee set employment_status='TERMINATED',termination_effective_on=? where id=?",today,e.employee().id());code=EMPLOYEE_TERMINATED;
        } else {close(e.assignmentId(),today.plusDays(1));fixtureAssignment(e.employee().id(),today.plusDays(1),null);code=HR_TIMELINE_CHANGE_UNSUPPORTED;}
        var before=employee(e.employee().id());var rows=history(e.employee().id());
        failure(code,()->service.changeAssignment(input(e)));unchanged(e,before,rows);
        failure(VERSION_CONFLICT,()->service.changeAssignment(request(e.employee().id(),1L,today,site2,department2,position2,"r")));
    }
    @Test void t29_invalidInputsDoNotWriteAndUnicodeReasonBoundaryPersists() {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());
        for(String bad:Arrays.asList(null,""," ","\u00a0","a\t","a\n","a\u0085","😀".repeat(501)))
            failure(INVALID_ASSIGNMENT_REASON,()->service.changeAssignment(request(e.employee().id(),0L,today,site2,department2,position2,bad)));
        for(Long bad:Arrays.asList(null,-1L)) failure(INVALID_EXPECTED_VERSION,()->service.changeAssignment(request(e.employee().id(),bad,today,site2,department2,position2,"r")));
        for(LocalDate bad:Arrays.asList(null,LocalDate.MAX,LocalDate.of(0,1,1)))
            failure(INVALID_DATE,()->service.changeAssignment(request(e.employee().id(),0L,bad,site2,department2,position2,"r")));
        for(LocalDate date:List.of(today.minusDays(1),today.plusDays(1)))
            failure(UNSUPPORTED_ASSIGNMENT_DATE,()->service.changeAssignment(request(e.employee().id(),0L,date,site2,department2,position2,"r")));
        failure(EMPLOYEE_NOT_FOUND,()->service.changeAssignment(request(UUID.randomUUID(),0L,today,site2,department2,position2,"r")));
        failure(INVALID_REFERENCE,()->service.changeAssignment(request(e.employee().id(),0L,today,null,department2,position2,"r")));
        unchanged(e,before,rows);
        var changed=service.changeAssignment(request(e.employee().id(),0L,today,site2,department2,position2,"  "+"😀".repeat(500)+"  "));
        assertThat(changed.current().reason()).isEqualTo("😀".repeat(500));assertThat(changed.previous().reason()).isEqualTo("최초 근거");
        var second=create("B");assertThat(service.changeAssignment(request(second.employee().id(),0L,today,site2,department2,position2,"한")).current().reason()).isEqualTo("한");
    }

    @ParameterizedTest @ValueSource(ints={0,1,2})
    void t10_t11_actualTransferCompetitionAndFirstRollback(int schedule) {
        var e=create("A");var held=gate();var go=gate();var n=new AtomicInteger();var firstPhysical=new AtomicReference<Map<String,Object>>();
        doAnswer(c->{var value=c.callRealMethod();if(n.incrementAndGet()==1){firstPhysical.set(physical());held.countDown();await(go);if(schedule==2)throw new IllegalStateException("abort winner");}return value;}).when(assignments).incrementEmployeeVersion(any());
        var one=input(e);var two=request(e.employee().id(),0L,today,site,department2,position,"alternative");
        var a=job(()->service.changeAssignment(schedule==1?two:one));await(held);
        var b=job(()->service.changeAssignment(schedule==1?one:two));blocked("from hr_employee where",(Integer)firstPhysical.get().get("pid"),null);go.countDown();
        Object winner=result(a),other=result(b);
        if(schedule==2) {assertThat(winner).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class);assertThat(other).isInstanceOf(AssignmentChanged.class);}
        else {assertThat(winner).isInstanceOf(AssignmentChanged.class);assertThat(other).isInstanceOfSatisfying(HrFailure.class,f->assertThat(f.code()).isEqualTo(VERSION_CONFLICT));}
        assertThat(employee(e.employee().id()).get("version")).isEqualTo(1L);assertThat(history(e.employee().id())).hasSize(2);
        assertThat(queries.findAssignmentAt(company,e.employee().id(),today)).isPresent();
    }
    @ParameterizedTest @CsvSource({"SITE,false","DEPARTMENT,false","POSITION,false","SITE,true","DEPARTMENT,true","POSITION,true"})
    void t12_transferGuardFirstCommitOrRollback(OrganizationTarget kind,boolean abort) {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());var held=gate();var go=gate();
        doAnswer(c->{c.callRealMethod();held.countDown();await(go);if(abort)throw new IllegalStateException("abort HR");return null;}).when(store).add(any(EmployeeAssignment.class));
        var a=job(()->service.changeAssignment(retainingTarget(e,kind)));await(held);var b=job(()->deactivate(kind));blocked("for update");go.countDown();
        assertThat(result(a)).isInstanceOf(abort?org.springframework.dao.InvalidDataAccessApiUsageException.class:AssignmentChanged.class);
        assertThat(result(b)).isInstanceOf(OrganizationStateResult.class);
        if(abort) unchanged(e,before,rows);else {assertThat(history(e.employee().id())).hasSize(2);assertThat(queries.findAssignmentAt(company,e.employee().id(),today)).isPresent();}
    }
    @ParameterizedTest @CsvSource({"SITE,false","DEPARTMENT,false","POSITION,false","SITE,true","DEPARTMENT,true","POSITION,true"})
    void t12_deactivateFirstCommitOrRollback(OrganizationTarget kind,boolean abort) {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());var held=gate();var go=gate();
        doAnswer(c->{c.callRealMethod();held.countDown();await(go);if(abort)throw new IllegalStateException("abort ORG");return null;})
                .when(locks).lockForState(eq(kind),eq(company),eq(target(kind)));
        var a=job(()->deactivate(kind));await(held);var b=job(()->service.changeAssignment(retainingTarget(e,kind)));blocked("for share");go.countDown();
        assertThat(result(a)).isInstanceOf(abort?org.springframework.dao.InvalidDataAccessApiUsageException.class:OrganizationStateResult.class);
        assertThat(result(b)).isInstanceOf(abort?AssignmentChanged.class:OrganizationFailure.class);
        if(!abort) unchanged(e,before,rows);else assertThat(history(e.employee().id())).hasSize(2);
    }
    @Test void t13_differentEmployeesShareOrganizationLocksWithoutGlobalSerialization() {
        var a=create("A");var b=create("B");var held=gate();var go=gate();
        doAnswer(c->{var value=c.callRealMethod();if(c.<Employee>getArgument(0).id().equals(a.employee().id())){held.countDown();await(go);}return value;}).when(assignments).incrementEmployeeVersion(any());
        var first=job(()->service.changeAssignment(input(a)));await(held);
        assertThat(result(job(()->service.changeAssignment(input(b))))).isInstanceOf(AssignmentChanged.class);
        assertThat(first.isDone()).isFalse();go.countDown();assertThat(result(first)).isInstanceOf(AssignmentChanged.class);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void t26_samePhysicalTransactionAndManagedProbeSurvivesCommitAndRollback(boolean abort) {
        var e=create("A");UUID probe=UUID.randomUUID();jdbc.update("insert into org03_probe.org03_test_probe values(?,'before')",probe);
        var ids=new ArrayList<Map<String,Object>>();var order=new ArrayList<String>();var managed=new AtomicReference<ManagedProbe>();
        doAnswer(c->{managed.set(em.find(ManagedProbe.class,probe));ids.add(physical());c.callRealMethod();return null;}).when(locks).requireWritableTransaction();
        doAnswer(c->{order.add(c.getArgument(0).toString());ids.add(physical());return c.callRealMethod();}).when(locks).lockForAssignment(any(),any(),any());
        doAnswer(c->{order.add("EMPLOYEE");ids.add(physical());assertThat(em.contains(managed.get())).isTrue();return c.callRealMethod();}).when(assignments).lockEmployee(any(),any());
        doAnswer(c->{ids.add(physical());assertThat(em.contains(managed.get())).isTrue();managed.get().label("after");c.callRealMethod();return null;}).when(assignments).incrementEmployeeVersion(any());
        doAnswer(c->{ids.add(physical());c.callRealMethod();if(abort)throw new IllegalStateException("abort");return null;}).when(store).flush();
        if(abort) assertThatThrownBy(()->service.changeAssignment(input(e))).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class);
        else service.changeAssignment(input(e));
        assertThat(order).containsExactly("SITE","DEPARTMENT","POSITION","EMPLOYEE");assertThat(ids.size()).isGreaterThanOrEqualTo(7);assertThat(ids).allMatch(ids.getFirst()::equals);
        assertThat(jdbc.queryForObject("select label from org03_probe.org03_test_probe where id=?",String.class,probe)).isEqualTo(abort?"before":"after");
        System.out.println("H34-EVIDENCE Root/Guard/Employee/force/flush same PID+XID="+ids+"; managed probe rollback="+abort);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void t05_t09_t30_preloadedEmployeeAndOldAssignmentRefreshAgainstIndependentEarlierDayCommit(boolean fresh) {
        var e=create("A");var beforeOld=history(e.employee().id()).getFirst();var staleEmployee=new AtomicReference<Employee>();var staleOld=new AtomicReference<EmployeeAssignment>();
        var bResult=new AtomicReference<AssignmentChanged>();var aPhysical=new AtomicReference<Map<String,Object>>();
        var bPhysical=new AtomicReference<Map<String,Object>>();Thread aThread=Thread.currentThread();
        UUID probe=UUID.randomUUID();jdbc.update("insert into org03_probe.org03_test_probe values(?,'before')",probe);var managedProbe=new AtomicReference<ManagedProbe>();
        Clock bClock=Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"),ZoneOffset.UTC);
        var bService=new EmployeeAssignmentService(store,assignments,guard,manager,bClock,30);
        doAnswer(c->{
            // Only A: Clock was captured as 11th; B is an independent production command with its own 10th Clock.
            if(Thread.currentThread()==aThread) {
                aPhysical.set(physical());staleEmployee.set(em.find(Employee.class,e.employee().id()));staleOld.set(em.find(EmployeeAssignment.class,e.assignmentId()));managedProbe.set(em.find(ManagedProbe.class,probe));
                assertThat(staleEmployee.get().identity().version()).isZero();assertThat(staleOld.get().effectiveTo()).isNull();
                var outcome=result(job(()->bService.changeAssignment(request(e.employee().id(),0L,today.minusDays(1),site2,department,position,"B committed"))));
                assertThat(outcome).isInstanceOf(AssignmentChanged.class);bResult.set((AssignmentChanged)outcome);
                assertThat(bResult.get().employeeVersion()).isEqualTo(1);assertThat(bResult.get().current().effectiveFrom()).isBefore(today);
                var query=queries.findAssignmentAt(company,e.employee().id(),today).orElseThrow();
                assertThat(query.employee().version()).isEqualTo(bResult.get().employeeVersion());assertThat(query.assignmentId()).isEqualTo(bResult.get().current().id());
                assertThat(staleEmployee.get().identity().version()).isZero();assertThat(staleOld.get().effectiveTo()).isNull();
            }
            return c.callRealMethod();
        }).when(locks).requireWritableTransaction();
        doAnswer(c->{if(Thread.currentThread()!=aThread)bPhysical.set(physical());return c.callRealMethod();}).when(assignments).incrementEmployeeVersion(any());
        doAnswer(c->{var value=c.callRealMethod();if(Thread.currentThread()==aThread) {
            assertThat(em.contains(managedProbe.get())).isTrue();assertThat(em.contains(staleEmployee.get())).isTrue();
            assertThat(staleEmployee.get().identity().version()).isEqualTo(bResult.get().employeeVersion());
            assertThat(aPhysical.get().get("pid")).isNotEqualTo(bPhysical.get().get("pid"));assertThat(aPhysical.get().get("xid")).isNotEqualTo(bPhysical.get().get("xid"));
        }return value;}).when(assignments).lockEmployee(any(),any());
        doAnswer(c->{var value=c.callRealMethod();if(Thread.currentThread()==aThread) {
            assertThat(em.contains(managedProbe.get())).isTrue();assertThat(em.contains(staleOld.get())).isTrue();
            assertThat(staleOld.get().effectiveTo()).isEqualTo(today.minusDays(1));assertThat(staleOld.get().version()).isEqualTo(1);
        }return value;}).when(assignments).freshTimeline(any(),any());
        // The version value 1 is the fixture's B commit result, asserted above before the write version check.
        var request=request(e.employee().id(),fresh?1L:0L,today,site2,department2,position2,"A applied");
        if(fresh) {
            var a=service.changeAssignment(request);assertThat(a.employeeVersion()).isEqualTo(2);assertThat(a.previous().id()).isEqualTo(bResult.get().current().id());
            assertThat(a.previous().effectiveTo()).isEqualTo(today);assertThat(a.current().effectiveFrom()).isEqualTo(today);
        } else failure(VERSION_CONFLICT,()->service.changeAssignment(request));
        var after=history(e.employee().id());assertThat(after).hasSize(fresh?3:2);
        var oldExpected=new HashMap<>(beforeOld);oldExpected.put("version",1L);oldExpected.put("effective_to",java.sql.Date.valueOf(today.minusDays(1)));oldExpected.put("updated_at",java.sql.Timestamp.from(bClock.instant()));
        assertThat(after.getFirst()).isEqualTo(oldExpected);
        System.out.println("H34-EVIDENCE R2 stale-context A date11/B date10; B old row preserved; freshExpected="+fresh+" A physical="+aPhysical.get()+" timeline="+after);
    }

    @Test void t08_insertBeforeCloseIsRejectedByImmediateExclude() {
        var e=create("A");var before=history(e.employee().id());
        sqlFailure("23P01","ex_hr_assignment_employee_period",()->fixtureAssignment(e.employee().id(),today,null));
        assertThat(history(e.employee().id())).isEqualTo(before);
        assertThat(jdbc.queryForObject("select condeferrable from pg_constraint where conname='ex_hr_assignment_employee_period'",Boolean.class)).isFalse();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void t27_r2ExactIsolatedFixtureExcludeWaitCommitOrRollback(boolean abort) {
        var e=create("A");close(e.assignmentId(),LocalDate.of(2026,10,5));
        assertThat(employee(e.employee().id()).get("joined_on")).isEqualTo(java.sql.Date.valueOf(joined));
        assertThat(history(e.employee().id())).hasSize(1);assertThat(history(e.employee().id()).getFirst().get("effective_from")).isEqualTo(java.sql.Date.valueOf(joined));
        var held=gate();var go=gate();var aPid=new AtomicInteger();var bPid=new AtomicInteger();var bStarted=gate();var aId=new AtomicReference<UUID>();
        var a=job(()->new TransactionTemplate(manager).execute(status->{aPid.set((int)physical().get("pid"));
            aId.set(fixtureAssignment(e.employee().id(),LocalDate.of(2026,10,5),LocalDate.of(2026,10,10)));
            held.countDown();await(go);if(abort)status.setRollbackOnly();return "A done";}));
        await(held);
        var b=job(()->tx(()->{bPid.set((int)physical().get("pid"));bStarted.countDown();return fixtureAssignment(e.employee().id(),LocalDate.of(2026,10,7),LocalDate.of(2026,10,12));}));
        await(bStarted);
        blocked("insert into hr_employee_assignment",aPid.get(),bPid.get());assertThat(aPid.get()).isNotEqualTo(bPid.get());go.countDown();
        assertThat(result(a)).isEqualTo("A done");Object outcome=result(b);
        if(abort) assertThat(outcome).isInstanceOf(UUID.class);
        else {assertThat(outcome).isInstanceOf(RuntimeException.class);var error=pg((Throwable)outcome);
            assertThat(error.getSQLState()).isEqualTo("23P01");assertThat(error.getServerErrorMessage().getConstraint()).isEqualTo("ex_hr_assignment_employee_period");
            assertThat(store.translate((RuntimeException)outcome)).isInstanceOfSatisfying(HrFailure.class,f->assertThat(f.code()).isEqualTo(ASSIGNMENT_PERIOD_CONFLICT));}
        assertThat(history(e.employee().id())).hasSize(2);
        System.out.println("H34-EVIDENCE T27 R2 joined/from=2026-10-01 initial=[01,05), A="+aId.get()+" [05,10) B=[07,12); A pid="+aPid+" B pid="+bPid+" A rollback="+abort+" result="+(abort?"B committed":"23P01 / ex_hr_assignment_employee_period")+" rows="+history(e.employee().id()));
    }
    @Test void t27_r2UpdateOverlapsOnlyAdjacentBAndPreservesRows() {
        var e=create("A");close(e.assignmentId(),LocalDate.of(2026,10,5));
        UUID a=fixtureAssignment(e.employee().id(),LocalDate.of(2026,10,5),LocalDate.of(2026,10,10));
        UUID b=fixtureAssignment(e.employee().id(),LocalDate.of(2026,10,10),LocalDate.of(2026,10,15));
        var before=history(e.employee().id());
        sqlFailure("23P01","ex_hr_assignment_employee_period",()->close(a,LocalDate.of(2026,10,11)));
        assertThat(history(e.employee().id())).isEqualTo(before);
        System.out.println("H34-EVIDENCE T27 R2 UPDATE A.to=11 rejected 23P01/ex_hr_assignment_employee_period; A="+a+" B="+b+" preserved="+before);
    }
    @Test void t24_actualDeferredCommitAbortRollsBackAllChanges() {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());
        jdbc.execute("create function hr03_test_abort_commit() returns trigger language plpgsql as $$ begin raise exception 'test commit abort' using errcode='23514'; return null; end $$");
        jdbc.execute("create constraint trigger hr03_test_abort after insert on hr_employee_assignment deferrable initially deferred for each row execute function hr03_test_abort_commit()");
        try {var error=catchThrowable(()->service.changeAssignment(input(e)));assertThat(pg(error).getSQLState()).isEqualTo("23514");assertThat(error).isNotInstanceOf(HrFailure.class);unchanged(e,before,rows);}
        finally {jdbc.execute("drop trigger hr03_test_abort on hr_employee_assignment");jdbc.execute("drop function hr03_test_abort_commit()");}
    }
    @Test void t23_t24_realCommitLostAckWrapperAndRetryIsNotReceipt() {
        var e=create("A");var commits=new AtomicInteger();var rollbacks=new AtomicInteger();
        PlatformTransactionManager lostAck=new PlatformTransactionManager(){
            public TransactionStatus getTransaction(TransactionDefinition d){return manager.getTransaction(d);}
            public void commit(TransactionStatus s){manager.commit(s);commits.incrementAndGet();throw new TransactionSystemException("test ACK lost AFTER real commit");}
            public void rollback(TransactionStatus s){rollbacks.incrementAndGet();manager.rollback(s);}
        };
        var wrapped=new EmployeeAssignmentService(store,assignments,guard,lostAck,clock,30);
        assertThatThrownBy(()->wrapped.changeAssignment(input(e))).isInstanceOf(TransactionSystemException.class);
        assertThat(commits.get()).isEqualTo(1);assertThat(rollbacks.get()).isZero();assertThat(history(e.employee().id())).hasSize(2);
        var before=employee(e.employee().id());var rows=history(e.employee().id());
        failure(VERSION_CONFLICT,()->service.changeAssignment(input(e)));
        states.deactivateSite(company,site2,0L,"inactive after commit");
        assertThatThrownBy(()->service.changeAssignment(input(e))).isInstanceOf(OrganizationFailure.class);
        when(clock.instant()).thenReturn(instant.plus(Duration.ofDays(1)));
        failure(UNSUPPORTED_ASSIGNMENT_DATE,()->service.changeAssignment(input(e)));unchanged(e,before,rows);
        System.out.println("H34-EVIDENCE actual commit + ACK-loss wrapper; stored v1/two rows; no second rollback/retry, retry errors depend on date/Guard; NOT real network test");
    }
    @Test void t25_actualEmployeeLockTimeoutRemainsTechnical() {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());var held=gate();var go=gate();
        var holder=job(()->tx(()->{jdbc.queryForObject("select id from hr_employee where id=? for update",UUID.class,e.employee().id());held.countDown();await(go);return "released";}));
        await(held);
        doAnswer(c->{c.callRealMethod();assertThat(jdbc.queryForObject("show lock_timeout",String.class)).isEqualTo("5s");assertThat(jdbc.queryForObject("show statement_timeout",String.class)).isEqualTo("30s");jdbc.execute("set local lock_timeout='800ms'");return null;}).when(assignments).boundRootWaits(anyInt());
        var waiting=job(()->service.changeAssignment(input(e)));blocked("from hr_employee where");var error=result(waiting);
        assertThat(error).isInstanceOf(RuntimeException.class).isNotInstanceOf(HrFailure.class);assertThat(pg((Throwable)error).getSQLState()).isEqualTo("55P03");
        go.countDown();assertThat(result(holder)).isEqualTo("released");unchanged(e,before,rows);
        System.out.println("H34-EVIDENCE real Employee FOR UPDATE wait / 55P03; default lock=5s statement=30s verified; test shortened lock only");
    }
    @Test void t25_actualDeadlockWithTestOnlyReverseLockerPreservesTechnicalFailure() {
        var e=create("A");var other=create("B");var before=employee(e.employee().id());var rows=history(e.employee().id());
        var force=gate();var reverseHeld=gate();var proceed=gate();
        doAnswer(c->{c.callRealMethod();jdbc.execute("set local deadlock_timeout='100ms'");force.countDown();await(proceed);
            jdbc.queryForObject("select id from hr_employee where id=? for update",UUID.class,other.employee().id());return null;}).when(assignments).incrementEmployeeVersion(any());
        var actual=job(()->service.changeAssignment(input(e)));await(force);
        var reverse=job(()->tx(()->{jdbc.execute("set local deadlock_timeout='100ms'");jdbc.queryForObject("select id from hr_employee where id=? for update",UUID.class,other.employee().id());reverseHeld.countDown();
            jdbc.queryForObject("select id from hr_employee where id=? for update",UUID.class,e.employee().id());return "reverse committed";}));
        await(reverseHeld);blocked("select id from hr_employee where id");proceed.countDown();
        var a=result(actual);var b=result(reverse);var errors=List.of(a,b).stream().filter(RuntimeException.class::isInstance).map(RuntimeException.class::cast).toList();
        assertThat(errors).hasSize(1);var error=errors.getFirst();assertThat(pg(error).getSQLState()).isEqualTo("40P01");assertThat(store.translate(error)).isSameAs(error);
        if(a instanceof RuntimeException)unchanged(e,before,rows);else {assertThat(a).isInstanceOf(AssignmentChanged.class);assertThat(history(e.employee().id())).hasSize(2);}
        assertThat(history(other.employee().id())).hasSize(1);
        System.out.println("H34-EVIDENCE real 40P01 with test-only reverse lock injection; production command outcome="+a.getClass().getSimpleName());
    }
    @Test void t25_actualConnectionTerminationAfterForceRollsBack() {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());
        doAnswer(c->{c.callRealMethod();int pid=(int)physical().get("pid");assertThat(result(job(()->jdbc.queryForObject("select pg_terminate_backend(?)",Boolean.class,pid)))).isEqualTo(true);return null;}).when(assignments).incrementEmployeeVersion(any());
        assertThatThrownBy(()->service.changeAssignment(input(e))).isInstanceOf(RuntimeException.class).isNotInstanceOf(HrFailure.class);unchanged(e,before,rows);
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3})
    void t26_invalidTransactionBoundariesRejected(int kind) {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());
        if(kind==0) tx(()->{failure(TRANSACTION_BOUNDARY_VIOLATION,()->service.changeAssignment(input(e)));return null;});
        else {
            PlatformTransactionManager incorrect=kind==3?new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()):new PlatformTransactionManager(){
                public TransactionStatus getTransaction(TransactionDefinition definition){var d=new DefaultTransactionDefinition(definition);if(kind==1)d.setReadOnly(true);else d.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);return manager.getTransaction(d);}
                public void commit(TransactionStatus status){manager.commit(status);}
                public void rollback(TransactionStatus status){manager.rollback(status);}
            };
            var wrong=new EmployeeAssignmentService(store,assignments,guard,incorrect,clock,30);
            assertThatThrownBy(()->wrong.changeAssignment(input(e))).isInstanceOfSatisfying(OrganizationFailure.class,f->assertThat(f.code()).isEqualTo(OrganizationFailure.Code.TRANSACTION_BOUNDARY_VIOLATION));
        }
        unchanged(e,before,rows);verify(assignments,never()).lockEmployee(any(),any());
    }
    @Test void t28_actualOtherConstraintsRemainTechnical() {
        var e=create("A");
        var attempts=List.<Runnable>of(
            ()->jdbc.update("update hr_employee_assignment set site_id=? where id=?",UUID.randomUUID(),e.assignmentId()),
            ()->jdbc.update("update hr_employee_assignment set effective_to=effective_from where id=?",e.assignmentId()),
            ()->jdbc.update("update hr_employee_assignment set reason='' where id=?",e.assignmentId()),
            ()->jdbc.update("insert into hr_employee select * from hr_employee where id=?",e.employee().id()));
        for(var action:attempts) {var error=(RuntimeException)catchThrowable(action::run);assertThat(pg(error).getSQLState()).isIn("23503","23514","23505");assertThat(store.translate(error)).isSameAs(error);}
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void t36_actualMidnightLockWaitRetainsRootDateAndRetryGetsNewDate(boolean sameDay) {
        var e=sameDay?registration.createEmployee(new CreateEmployee(company,"A","fixture",today,site,department,position,"r")):create("A");
        var before=employee(e.employee().id());var rows=history(e.employee().id());var held=gate();var go=gate();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-11T14:59:00Z"));clearInvocations(clock);
        var blocker=job(()->tx(()->{jdbc.queryForObject("select id from hr_employee where id=? for update",UUID.class,e.employee().id());held.countDown();await(go);return "released";}));await(held);
        var changing=job(()->service.changeAssignment(input(e)));blocked("from hr_employee where");when(clock.instant()).thenReturn(Instant.parse("2026-10-11T15:01:00Z"));go.countDown();assertThat(result(blocker)).isEqualTo("released");
        Object outcome=result(changing);
        if(sameDay){assertThat(outcome).isInstanceOfSatisfying(HrFailure.class,f->assertThat(f.code()).isEqualTo(SAME_DAY_ASSIGNMENT_CHANGE_UNSUPPORTED));unchanged(e,before,rows);}
        else {assertThat(outcome).isInstanceOfSatisfying(AssignmentChanged.class,c->assertThat(c.appliedOn()).isEqualTo(today));assertThat(history(e.employee().id()).getLast().get("effective_from")).isEqualTo(java.sql.Date.valueOf(today));}
        verify(clock,times(1)).instant();failure(UNSUPPORTED_ASSIGNMENT_DATE,()->service.changeAssignment(input(e)));
        System.out.println("H34-EVIDENCE actual lock wait across controlled Seoul 11th23:59 ->12th00:01; root date11 retained, sameDay="+sameDay+"; new retry rejected old date");
    }

    @Test void t30_readerCannotObserveUncommittedClosedTailAndUsesCommittedScalarSnapshot() {
        var e=create("A");var held=gate();var go=gate();var n=new AtomicInteger();
        doAnswer(c->{c.callRealMethod();if(n.incrementAndGet()==1){held.countDown();await(go);}return null;}).when(store).flush();
        var changing=job(()->service.changeAssignment(input(e)));await(held);
        tx(()->{
            var managed=em.find(Employee.class,e.employee().id());var old=em.find(EmployeeAssignment.class,e.assignmentId());
            var before=queries.findAssignmentAt(company,e.employee().id(),today).orElseThrow();
            assertThat(before.employee().version()).isZero();assertThat(before.assignmentId()).isEqualTo(e.assignmentId());assertThat(before.effectiveTo()).isNull();
            go.countDown();var changed=(AssignmentChanged)result(changing);
            var after=queries.findAssignmentAt(company,e.employee().id(),today).orElseThrow();
            assertThat(after.employee().version()).isEqualTo(changed.employeeVersion());assertThat(after.assignmentId()).isEqualTo(changed.current().id());
            assertThat(managed.identity().version()).isZero();assertThat(old.effectiveTo()).isNull();assertThat(em.contains(managed)).isTrue();assertThat(em.contains(old)).isTrue();
            return null;
        });
    }
    @Test void t26_guardFailureCaughtInsideConsumerStillAbortsEntireRoot() {
        var e=create("A");deactivate(OrganizationTarget.SITE);var before=employee(e.employee().id());var rows=history(e.employee().id());
        // Exercise the actual Guard's rollback-only contract; it must not permit a consumer to swallow INACTIVE.
        assertThatThrownBy(()->tx(()->{
            try {guard.requireAssignable(new OrganizationAssignmentTargets(company,site,department,position));}catch(OrganizationFailure ignored) {}
            jdbc.update("update hr_employee set display_name='must rollback' where id=?",e.employee().id());return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        unchanged(e,before,rows);
    }
}
