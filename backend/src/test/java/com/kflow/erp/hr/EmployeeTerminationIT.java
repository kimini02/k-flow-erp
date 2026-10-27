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
@Import(EmployeeTerminationIT.ProbeConfiguration.class)
@Timeout(60)
class EmployeeTerminationIT {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16")
            .withInitScript("org03-probe.sql").withCommand("postgres","-c","log_statement=all","-c","log_line_prefix=%p %x ");
    @TestConfiguration @EntityScan(basePackageClasses={KFlowErpApplication.class,ManagedProbe.class})
    static class ProbeConfiguration {}
    @Autowired EmployeeAssignmentService transfers;
    @Autowired EmployeeTerminationService service;
    @Autowired EmployeeRegistrationService registration;
    @Autowired EmployeeQueryService queries;
    @MockitoSpyBean OrganizationService organization;
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

    TerminateEmployee end(EmployeeCreated e) {return ending(e.employee().id(),0L,today,"  퇴사 근거  ");}
    TerminateEmployee ending(UUID id,Long v,LocalDate date,String reason) {return new TerminateEmployee(company,id,v,date,reason);}
    EmployeeTerminationService withManager(PlatformTransactionManager m) {return new EmployeeTerminationService(store,assignments,organization,m,clock,30);}

    @Test void t14_t19_am02_atomicDirtyUpdateVersionsGenerationAndExclusiveQueries() {
        var e=create("A");var before=employee(e.employee().id());var old=history(e.employee().id()).getFirst();
        assertThat(before.get("auth_revocation_generation")).isEqualTo(0L);
        var ids=new ArrayList<Map<String,Object>>();var seen=new AtomicInteger();
        doAnswer(c->{c.callRealMethod();ids.add(physical());return null;}).when(assignments).requireWritableTransaction();
        doAnswer(c->{var value=c.callRealMethod();ids.add(physical());return value;}).when(assignments).lockEmployee(any(),any());
        doAnswer(c->{c.callRealMethod();ids.add(physical());if(seen.incrementAndGet()==2) {
            assertThat(employee(e.employee().id()).get("version")).isEqualTo(1L);
            assertThat(employee(e.employee().id()).get("auth_revocation_generation")).isEqualTo(1L);
        }return null;}).when(store).flush();
        var result=service.terminateEmployee(end(e));
        assertThat(result.employmentStatus()).isEqualTo("TERMINATED");assertThat(result.terminationEffectiveOn()).isEqualTo(today);
        assertThat(result.employeeVersion()).isEqualTo(1);assertThat(result.lastAssignment().version()).isEqualTo(1);
        assertThat(result.lastAssignment().id()).isEqualTo(e.assignmentId());assertThat(result.lastAssignment().reason()).isEqualTo("최초 근거");
        assertThat(result.lastAssignment().effectiveFrom()).isEqualTo(joined);assertThat(result.lastAssignment().effectiveTo()).isEqualTo(today);
        var expected=new HashMap<>(before);expected.put("employment_status","TERMINATED");expected.put("termination_effective_on",java.sql.Date.valueOf(today));
        expected.put("termination_reason","퇴사 근거");expected.put("auth_revocation_generation",1L);expected.put("version",1L);expected.put("updated_at",java.sql.Timestamp.from(instant));
        assertThat(employee(e.employee().id())).isEqualTo(expected);
        var closed=new HashMap<>(old);closed.put("effective_to",java.sql.Date.valueOf(today));closed.put("version",1L);closed.put("updated_at",java.sql.Timestamp.from(instant));
        assertThat(history(e.employee().id())).containsExactly(closed);
        assertThat(queries.findEmployee(company,e.employee().id()).orElseThrow().employmentStatus()).isEqualTo("TERMINATED");
        assertThat(queries.findAssignmentAt(company,e.employee().id(),today.minusDays(1))).isPresent();
        assertThat(queries.findAssignmentAt(company,e.employee().id(),today)).isEmpty();assertThat(queries.findAssignmentAt(company,e.employee().id(),today.plusDays(1))).isEmpty();
        assertThat(queries.employees(company,"TERMINATED",0,20).items()).hasSize(1);
        verify(assignments,never()).incrementEmployeeVersion(any());verify(clock,times(1)).instant();
        assertThat(ids).hasSize(4).allMatch(ids.getFirst()::equals);
        String key=ids.getFirst().get("pid")+" "+ids.getFirst().get("xid")+" ";
        String sql=postgres.getLogs().lines().filter(l->l.contains(key)).reduce("",(a,b)->a+b+"\n");
        var updates=sql.lines().filter(l->l.contains("update hr_employee set")).toList();
        assertThat(updates).hasSize(1);assertThat(updates.getFirst()).contains("auth_revocation_generation","employment_status","termination_effective_on","termination_reason","version");
        assertThat(sql.lines().filter(l->l.contains("update hr_employee_assignment set")).count()).isEqualTo(1);
        assertThat(sql.indexOf("update hr_employee_assignment")).isLessThan(sql.indexOf("update hr_employee set"));
        assertThat(sql).doesNotContain("insert into hr_employee_assignment","FOR SHARE");
        System.out.println("H34-04 EVIDENCE T14/T19/A3-T07,T35 HR-only: same PID/XID="+ids+" Employee version0->1 generation0->1, tail0->1; one Employee dirty UPDATE\n"+sql);
    }
    @ParameterizedTest @ValueSource(ints={1,2})
    void t18_am02_eachFlushFailureRollsBackAllDataAndGeneration(int point) {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());var n=new AtomicInteger();
        doAnswer(c->{c.callRealMethod();if(n.incrementAndGet()==point)throw new IllegalStateException("injected after flush "+point);return null;}).when(store).flush();
        assertThatThrownBy(()->service.terminateEmployee(end(e))).isInstanceOf(RuntimeException.class).hasRootCauseInstanceOf(IllegalStateException.class);
        unchanged(e,before,rows);System.out.println("H34-04 EVIDENCE T18/A3-T08 flush="+point+" independent DB employee/tail/reason/version/generation fully rolled back");
    }
    @Test void t18_t24_actualCommitAbortRollsBackBothUpdates() {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());
        jdbc.execute("create function hr04_test_abort_commit() returns trigger language plpgsql as $$ begin raise exception 'test commit abort' using errcode='23514'; return null; end $$");
        jdbc.execute("create constraint trigger hr04_test_abort after update on hr_employee deferrable initially deferred for each row execute function hr04_test_abort_commit()");
        try {var error=catchThrowable(()->service.terminateEmployee(end(e)));assertThat(pg(error).getSQLState()).isEqualTo("23514");assertThat(error).isNotInstanceOf(HrFailure.class);unchanged(e,before,rows);}
        finally {jdbc.execute("drop trigger hr04_test_abort on hr_employee");jdbc.execute("drop function hr04_test_abort_commit()");}
        System.out.println("H34-04 EVIDENCE T24 real PG deferred commit abort 23514; no success DTO, all HR changes rolled back");
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t17_inactiveReferencesDoNotBlockTerminationOrMutateOrganization(OrganizationTarget kind) {
        var e=create("A");deactivate(kind);var before=jdbc.queryForList("select * from organization_"+kind.name().toLowerCase()+" order by id");
        clearInvocations(locks);service.terminateEmployee(end(e));
        verify(locks,never()).lockForAssignment(any(),any(),any());verify(locks,never()).lockForState(any(),any(),any());
        assertThat(jdbc.queryForList("select * from organization_"+kind.name().toLowerCase()+" order by id")).isEqualTo(before);
    }
    @Test void t15_t16_t21_inputDateCompanyStaleAndAlreadyTerminatedPrecedence() {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());
        for(String bad:Arrays.asList(null,""," ","\u00a0","\u3000","a\t","a\n","a\u0085","\uD800","😀".repeat(501)))
            failure(INVALID_TERMINATION_REASON,()->service.terminateEmployee(ending(e.employee().id(),0L,today,bad)));
        for(Long bad:Arrays.asList(null,-1L))failure(INVALID_EXPECTED_VERSION,()->service.terminateEmployee(ending(e.employee().id(),bad,today,"r")));
        for(LocalDate bad:Arrays.asList(null,LocalDate.MAX,LocalDate.of(0,1,1)))failure(INVALID_DATE,()->service.terminateEmployee(ending(e.employee().id(),0L,bad,"r")));
        for(LocalDate d:List.of(today.minusDays(1),today.plusDays(1)))failure(UNSUPPORTED_TERMINATION_DATE,()->service.terminateEmployee(ending(e.employee().id(),0L,d,"r")));
        failure(INVALID_REFERENCE,()->service.terminateEmployee(null));failure(INVALID_REFERENCE,()->service.terminateEmployee(ending(null,0L,today,"r")));
        failure(EMPLOYEE_NOT_FOUND,()->service.terminateEmployee(ending(UUID.randomUUID(),0L,today,"r")));
        assertThatThrownBy(()->service.terminateEmployee(new TerminateEmployee(UUID.randomUUID(),e.employee().id(),0L,today,"r")))
                .isInstanceOfSatisfying(OrganizationFailure.class,f->assertThat(f.code()).isEqualTo(OrganizationFailure.Code.COMPANY_NOT_FOUND));
        unchanged(e,before,rows);service.terminateEmployee(end(e));before=employee(e.employee().id());rows=history(e.employee().id());
        failure(VERSION_CONFLICT,()->service.terminateEmployee(end(e)));
        failure(ALREADY_TERMINATED,()->service.terminateEmployee(ending(e.employee().id(),1L,today,"other reason")));
        failure(INVALID_TERMINATION_REASON,()->service.terminateEmployee(ending(e.employee().id(),1L,today,null)));unchanged(e,before,rows);
    }
    @ParameterizedTest @ValueSource(ints={1,500})
    void t29_unicodeCodepointReasonBoundariesAndAsciiTrim(int length) {
        var e=create("A");String reason="😀".repeat(length);
        service.terminateEmployee(ending(e.employee().id(),0L,today,"  "+reason+"  "));
        assertThat(employee(e.employee().id()).get("termination_reason")).isEqualTo(reason);
        assertThat(history(e.employee().id()).getFirst().get("reason")).isEqualTo("최초 근거");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void t16_joiningOrTransferDayCannotTerminate(boolean transfer) {
        var e=transfer?create("A"):registration.createEmployee(new CreateEmployee(company,"A","fixture",today,site,department,position,"r"));
        if(transfer)transfers.changeAssignment(input(e));
        var before=employee(e.employee().id());var rows=history(e.employee().id());
        if(transfer)failure(VERSION_CONFLICT,()->service.terminateEmployee(end(e)));
        failure(transfer?SAME_DAY_ASSIGNMENT_TERMINATION_UNSUPPORTED:SAME_DAY_TERMINATION_UNSUPPORTED,
                ()->service.terminateEmployee(ending(e.employee().id(),transfer?1L:0L,today,"r")));unchanged(e,before,rows);
    }
    @ParameterizedTest @ValueSource(strings={"ON_LEAVE","FUTURE","gap","first","outside","missing","closed"})
    void t09_t22_unsupportedStateOrTimelineRejectedWithoutRepair(String kind) {
        var e=create("A");HrFailure.Code code=HR_ASSIGNMENT_INTEGRITY_VIOLATION;
        switch(kind){
            case "ON_LEAVE" -> {jdbc.update("update hr_employee set employment_status='ON_LEAVE' where id=?",e.employee().id());code=EMPLOYMENT_STATE_UNSUPPORTED;}
            case "FUTURE" -> {close(e.assignmentId(),today.plusDays(1));fixtureAssignment(e.employee().id(),today.plusDays(1),null);code=HR_TIMELINE_CHANGE_UNSUPPORTED;}
            case "gap" -> {close(e.assignmentId(),joined.plusDays(2));fixtureAssignment(e.employee().id(),joined.plusDays(3),null);}
            case "first" -> jdbc.update("update hr_employee_assignment set effective_from=? where id=?",joined.plusDays(1),e.assignmentId());
            case "outside" -> jdbc.update("update hr_employee_assignment set effective_from=? where id=?",joined.minusDays(1),e.assignmentId());
            case "missing" -> jdbc.update("delete from hr_employee_assignment where id=?",e.assignmentId());
            case "closed" -> close(e.assignmentId(),today.minusDays(1));
        }
        var before=employee(e.employee().id());var rows=history(e.employee().id());failure(code,()->service.terminateEmployee(end(e)));unchanged(e,before,rows);
    }
    @ParameterizedTest @CsvSource({"false,false","true,false","false,true","true,true"})
    void t20_realTransferVsTerminationBothWinnersAndFirstRollback(boolean terminationFirst,boolean abort) {
        compete(terminationFirst,false,abort);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void t21_realTerminationVsTerminationAndFirstRollback(boolean abort) {compete(true,true,abort);}
    void compete(boolean terminationFirst,boolean bothTerminate,boolean abort) {
        var e=create("A");var held=gate();var go=gate();var n=new AtomicInteger();var aId=new AtomicReference<Map<String,Object>>();var bId=new AtomicReference<Map<String,Object>>();
        doAnswer(c->{var value=c.callRealMethod();if(n.incrementAndGet()==1){aId.set(physical());held.countDown();await(go);if(abort)throw new IllegalStateException("first abort");}else bId.set(physical());return value;})
                .when(assignments).lockEmployee(any(),any());
        var a=job(()->terminationFirst?service.terminateEmployee(end(e)):transfers.changeAssignment(input(e)));await(held);
        var b=job(()->bothTerminate||!terminationFirst?service.terminateEmployee(end(e)):transfers.changeAssignment(input(e)));
        blocked("from hr_employee where",(Integer)aId.get().get("pid"),null);go.countDown();var first=result(a);var second=result(b);
        assertThat(bId.get().get("pid")).isNotEqualTo(aId.get().get("pid"));assertThat(bId.get().get("xid")).isNotEqualTo(aId.get().get("xid"));
        boolean terminated=abort?(bothTerminate||!terminationFirst):terminationFirst;
        if(abort){assertThat(first).isInstanceOf(RuntimeException.class);assertThat(second).isInstanceOf(terminated?EmployeeTerminated.class:AssignmentChanged.class);}
        else{assertThat(first).isInstanceOf(terminationFirst?EmployeeTerminated.class:AssignmentChanged.class);assertThat(second).isInstanceOfSatisfying(HrFailure.class,f->assertThat(f.code()).isEqualTo(VERSION_CONFLICT));}
        assertThat(employee(e.employee().id()).get("version")).isEqualTo(1L);
        assertThat(employee(e.employee().id()).get("auth_revocation_generation")).isEqualTo(terminated?1L:0L);
        assertThat(history(e.employee().id())).hasSize(terminated?1:2);
        failure(terminated?ALREADY_TERMINATED:SAME_DAY_ASSIGNMENT_TERMINATION_UNSUPPORTED,()->service.terminateEmployee(ending(e.employee().id(),1L,today,"second reason")));
        if(terminated)failure(EMPLOYEE_TERMINATED,()->transfers.changeAssignment(request(e.employee().id(),1L,today,site2,department2,position2,"r")));
        System.out.println("H34-04 EVIDENCE T20/T21 actual distinct PID/XID A="+aId.get()+" B="+bId.get()+" terminationFirst="+terminationFirst+" bothTerminate="+bothTerminate+" abort="+abort+" final generation="+(terminated?1:0));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void t05_r2_preloadedEmployeeOldTailRefreshAfterEarlierDayTransfer(boolean fresh) {
        var e=create("A");var oldBefore=history(e.employee().id()).getFirst();var aEmployee=new AtomicReference<Employee>();var aOld=new AtomicReference<EmployeeAssignment>();
        var bResult=new AtomicReference<AssignmentChanged>();var aId=new AtomicReference<Map<String,Object>>();var bId=new AtomicReference<Map<String,Object>>();
        UUID probe=UUID.randomUUID();jdbc.update("insert into org03_probe.org03_test_probe values(?,'before')",probe);var managed=new AtomicReference<ManagedProbe>();
        Clock bClock=Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"),ZoneOffset.UTC);
        var bService=new EmployeeAssignmentService(store,assignments,guard,manager,bClock,30);
        Thread aThread=Thread.currentThread();
        doAnswer(c->{c.callRealMethod();aId.set(physical());aEmployee.set(em.find(Employee.class,e.employee().id()));aOld.set(em.find(EmployeeAssignment.class,e.assignmentId()));managed.set(em.find(ManagedProbe.class,probe));
            var outcome=result(job(()->bService.changeAssignment(request(e.employee().id(),0L,today.minusDays(1),site2,department,position,"B transfer"))));
            assertThat(outcome).isInstanceOf(AssignmentChanged.class);bResult.set((AssignmentChanged)outcome);
            assertThat(aEmployee.get().identity().version()).isZero();assertThat(aOld.get().effectiveTo()).isNull();
            var scalar=queries.findAssignmentAt(company,e.employee().id(),today).orElseThrow();
            assertThat(scalar.employee().version()).isEqualTo(1);assertThat(scalar.assignmentId()).isEqualTo(bResult.get().current().id());
            managed.get().label("after");return null;
        }).when(assignments).requireWritableTransaction();
        doAnswer(c->{if(Thread.currentThread()!=aThread)bId.set(physical());return c.callRealMethod();}).when(assignments).incrementEmployeeVersion(any());
        doAnswer(c->{var value=c.callRealMethod();if(Thread.currentThread()==aThread){assertThat(em.contains(managed.get())).isTrue();assertThat(aEmployee.get().identity().version()).isEqualTo(1);}
            return value;}).when(assignments).lockEmployee(any(),any());
        doAnswer(c->{var value=c.callRealMethod();if(Thread.currentThread()==aThread){assertThat(em.contains(aOld.get())).isTrue();assertThat(aOld.get().effectiveTo()).isEqualTo(today.minusDays(1));assertThat(aOld.get().version()).isEqualTo(1);}
            return value;}).when(assignments).freshTimeline(any(),any());
        if(fresh){var r=service.terminateEmployee(ending(e.employee().id(),1L,today,"A end"));assertThat(r.employeeVersion()).isEqualTo(2);assertThat(r.lastAssignment().id()).isEqualTo(bResult.get().current().id());assertThat(r.lastAssignment().version()).isEqualTo(1);}
        else failure(VERSION_CONFLICT,()->service.terminateEmployee(end(e)));
        var expectedOld=new HashMap<>(oldBefore);expectedOld.put("version",1L);expectedOld.put("effective_to",java.sql.Date.valueOf(today.minusDays(1)));expectedOld.put("updated_at",java.sql.Timestamp.from(bClock.instant()));
        assertThat(history(e.employee().id())).hasSize(2);assertThat(history(e.employee().id()).getFirst()).isEqualTo(expectedOld);
        assertThat(jdbc.queryForObject("select label from org03_probe.org03_test_probe where id=?",String.class,probe)).isEqualTo(fresh?"after":"before");
        assertThat(aId.get().get("pid")).isNotEqualTo(bId.get().get("pid"));assertThat(aId.get().get("xid")).isNotEqualTo(bId.get().get("xid"));
        System.out.println("H34-04 EVIDENCE R2 A date11/B date10 actual transfer committed; fresh="+fresh+" closed only newest tail; B old row preserved; probe "+(fresh?"commit":"rollback")+" A="+aId.get()+" B="+bId.get());
    }
    @Test void t30_singleScalarQuerySeesOnlyBeforeOrAfterTerminationDespiteStaleEntities() {
        var e=create("A");var held=gate();var go=gate();var n=new AtomicInteger();
        doAnswer(c->{c.callRealMethod();if(n.incrementAndGet()==1){held.countDown();await(go);}return null;}).when(store).flush();
        var writer=job(()->service.terminateEmployee(end(e)));await(held);
        tx(()->{
            var managed=em.find(Employee.class,e.employee().id());var old=em.find(EmployeeAssignment.class,e.assignmentId());
            assertThat(queries.findAssignmentAt(company,e.employee().id(),today).orElseThrow().employee().version()).isZero();
            go.countDown();assertThat(result(writer)).isInstanceOf(EmployeeTerminated.class);
            assertThat(queries.findAssignmentAt(company,e.employee().id(),today)).isEmpty();
            var prior=queries.findAssignmentAt(company,e.employee().id(),today.minusDays(1)).orElseThrow();
            assertThat(prior.employee().version()).isEqualTo(1);assertThat(prior.employee().employmentStatus()).isEqualTo("TERMINATED");assertThat(prior.effectiveTo()).isEqualTo(today);
            assertThat(managed.identity().version()).isZero();assertThat(old.effectiveTo()).isNull();assertThat(em.contains(old)).isTrue();return null;
        });
    }
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void t36_midnightWaitPreservesOriginalDateAndSameDayRestrictions(int mode) {
        var e=mode==1?registration.createEmployee(new CreateEmployee(company,"A","fixture",today,site,department,position,"r")):create("A");
        if(mode==2)transfers.changeAssignment(input(e));
        var before=employee(e.employee().id());var rows=history(e.employee().id());var held=gate();var go=gate();
        when(clock.instant()).thenReturn(Instant.parse("2026-10-11T14:59:00Z"));clearInvocations(clock);
        var holder=job(()->tx(()->{jdbc.queryForObject("select id from hr_employee where id=? for update",UUID.class,e.employee().id());held.countDown();await(go);return true;}));await(held);
        var worker=job(()->service.terminateEmployee(ending(e.employee().id(),mode==2?1L:0L,today,"r")));
        blocked("from hr_employee where");when(clock.instant()).thenReturn(Instant.parse("2026-10-11T15:01:00Z"));go.countDown();assertThat(result(holder)).isEqualTo(true);
        var outcome=result(worker);
        if(mode==1||mode==2){assertThat(outcome).isInstanceOfSatisfying(HrFailure.class,f->assertThat(f.code()).isEqualTo(mode==1?SAME_DAY_TERMINATION_UNSUPPORTED:SAME_DAY_ASSIGNMENT_TERMINATION_UNSUPPORTED));unchanged(e,before,rows);}
        else{assertThat(outcome).isInstanceOfSatisfying(EmployeeTerminated.class,t->assertThat(t.terminationEffectiveOn()).isEqualTo(today));assertThat(employee(e.employee().id()).get("updated_at")).isEqualTo(java.sql.Timestamp.from(Instant.parse("2026-10-11T14:59:00Z")));}
        verify(clock,times(1)).instant();failure(UNSUPPORTED_TERMINATION_DATE,()->service.terminateEmployee(end(e)));
        System.out.println("H34-04 EVIDENCE T36 actual Employee lock wait controlled Seoul 11th23:59->12th00:01; mode="+mode+" root d11 retained; new retry d12 rejected e11");
    }
    @Test void t23_t24_realCommitThenLostAckWrapperDoesNotRetryOrCompensate() {
        var e=create("A");var commits=new AtomicInteger();var rollbacks=new AtomicInteger();
        var wrapped=withManager(new PlatformTransactionManager(){
            public TransactionStatus getTransaction(TransactionDefinition d){return manager.getTransaction(d);}
            public void commit(TransactionStatus s){manager.commit(s);commits.incrementAndGet();throw new TransactionSystemException("test lost ACK AFTER actual commit");}
            public void rollback(TransactionStatus s){rollbacks.incrementAndGet();manager.rollback(s);}
        });
        assertThatThrownBy(()->wrapped.terminateEmployee(end(e))).isInstanceOf(TransactionSystemException.class);
        assertThat(commits.get()).isEqualTo(1);assertThat(rollbacks.get()).isZero();assertThat(employee(e.employee().id()).get("auth_revocation_generation")).isEqualTo(1L);
        var before=employee(e.employee().id());var rows=history(e.employee().id());
        failure(VERSION_CONFLICT,()->service.terminateEmployee(end(e)));failure(ALREADY_TERMINATED,()->service.terminateEmployee(ending(e.employee().id(),1L,today,"changed reason")));
        unchanged(e,before,rows);System.out.println("H34-04 EVIDENCE T23/T24 actual commit + lost-ACK wrapper, not real network; no retry/compensation; generation stays1");
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4})
    void t26_wrongTransactionManagerOrBoundaryRejectedBeforeEmployeeLock(int kind) {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());
        clearInvocations(organization);
        if(kind==0)tx(()->{failure(TRANSACTION_BOUNDARY_VIOLATION,()->service.terminateEmployee(end(e)));return null;});
        else {
            PlatformTransactionManager wrong=kind==3?new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()):new PlatformTransactionManager(){
                public TransactionStatus getTransaction(TransactionDefinition d){
                    var changed=new DefaultTransactionDefinition(d);
                    if(kind==1)changed.setReadOnly(true);else if(kind==2)changed.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    if(kind==4)return new org.springframework.transaction.support.SimpleTransactionStatus();
                    return manager.getTransaction(changed);
                }
                public void commit(TransactionStatus s){if(kind!=4)manager.commit(s);}
                public void rollback(TransactionStatus s){if(kind!=4)manager.rollback(s);}
            };
            failure(TRANSACTION_BOUNDARY_VIOLATION,()->withManager(wrong).terminateEmployee(end(e)));
        }
        unchanged(e,before,rows);verify(assignments,never()).lockEmployee(any(),any());verify(organization,never()).getCompany();
    }
    @ParameterizedTest @CsvSource({"2,false,true","4,false,false","2,true,false"})
    void t26_nullSpringIsolationStillChecksActualConnection(int isolation,boolean readOnly,boolean accepted) {
        var tx=new TransactionTemplate(manager);tx.setIsolationLevel(isolation);tx.setReadOnly(readOnly);
        tx.execute(s->{Integer previous=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            boolean previousReadOnly=TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            try {TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
                if(accepted)assignments.requireWritableTransaction();else failure(TRANSACTION_BOUNDARY_VIOLATION,()->assignments.requireWritableTransaction());
            }finally {TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(previous);TransactionSynchronizationManager.setCurrentTransactionReadOnly(previousReadOnly);}
            s.setRollbackOnly();return null;
        });
    }
    @Test void t26_noTransactionRejected() {failure(TRANSACTION_BOUNDARY_VIOLATION,()->assignments.requireWritableTransaction());}
    @Test void t26_localRefreshDoesNotAutoFlushUnrelatedDirtyManagedProbe() {
        var e=create("A");UUID id=UUID.randomUUID();jdbc.update("insert into org03_probe.org03_test_probe values(?,'before')",id);
        tx(()->{var probe=em.find(ManagedProbe.class,id);probe.label("dirty");var flush=em.getFlushMode();
            assignments.requireWritableTransaction();assignments.lockEmployee(company,e.employee().id());assignments.freshTimeline(company,e.employee().id());
            assertThat(em.contains(probe)).isTrue();assertThat(em.getFlushMode()).isEqualTo(flush);
            assertThat(jdbc.queryForObject("select label from org03_probe.org03_test_probe where id=?",String.class,id)).isEqualTo("before");return null;});
        assertThat(jdbc.queryForObject("select label from org03_probe.org03_test_probe where id=?",String.class,id)).isEqualTo("dirty");
    }
    @Test void t25_actualLockTimeoutTechnical() {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());var held=gate();var go=gate();
        var a=job(()->tx(()->{jdbc.queryForObject("select id from hr_employee where id=? for update",UUID.class,e.employee().id());held.countDown();await(go);return true;}));await(held);
        doAnswer(c->{c.callRealMethod();assertThat(jdbc.queryForObject("show lock_timeout",String.class)).isEqualTo("5s");assertThat(jdbc.queryForObject("show statement_timeout",String.class)).isEqualTo("30s");jdbc.execute("set local lock_timeout='800ms'");return null;}).when(assignments).boundRootWaits(anyInt());
        var b=job(()->service.terminateEmployee(end(e)));blocked("from hr_employee where");var failure=result(b);
        assertThat(failure).isInstanceOf(RuntimeException.class).isNotInstanceOf(HrFailure.class);assertThat(pg((Throwable)failure).getSQLState()).isEqualTo("55P03");
        go.countDown();assertThat(result(a)).isEqualTo(true);unchanged(e,before,rows);
        System.out.println("H34-04 EVIDENCE T25 actual 55P03; bounded defaults lock5s/statement30s, test timeout800ms only");
    }
    @Test void t25_actualDeadlockTechnical() {
        var e=create("A");var b=create("B");var before=employee(e.employee().id());var rows=history(e.employee().id());
        var held=gate();var reverseHeld=gate();var go=gate();var n=new AtomicInteger();
        doAnswer(c->{c.callRealMethod();if(n.incrementAndGet()==1){jdbc.execute("set local deadlock_timeout='100ms'");held.countDown();await(go);jdbc.queryForObject("select id from hr_employee where id=? for update",UUID.class,b.employee().id());}return null;}).when(store).flush();
        var actual=job(()->service.terminateEmployee(end(e)));await(held);
        var reverse=job(()->tx(()->{jdbc.execute("set local deadlock_timeout='100ms'");jdbc.queryForObject("select id from hr_employee where id=? for update",UUID.class,b.employee().id());reverseHeld.countDown();jdbc.queryForObject("select id from hr_employee where id=? for update",UUID.class,e.employee().id());return true;}));
        await(reverseHeld);blocked("from hr_employee where");go.countDown();var first=result(actual);var second=result(reverse);
        var failures=List.of(first,second).stream().filter(RuntimeException.class::isInstance).map(RuntimeException.class::cast).toList();assertThat(failures).hasSize(1);
        assertThat(pg(failures.getFirst()).getSQLState()).isEqualTo("40P01");assertThat(store.translate(failures.getFirst())).isSameAs(failures.getFirst());
        if(first instanceof RuntimeException)unchanged(e,before,rows);else assertThat(first).isInstanceOf(EmployeeTerminated.class);
        System.out.println("H34-04 EVIDENCE T25 actual 40P01 from test-only reverse locker; actual termination outcome="+first.getClass().getSimpleName());
    }
    @ParameterizedTest @ValueSource(ints={1,2})
    void t25_realBackendConnectionKilledAfterFlushRollsBack(int point) {
        var e=create("A");var before=employee(e.employee().id());var rows=history(e.employee().id());var n=new AtomicInteger();
        doAnswer(c->{c.callRealMethod();if(n.incrementAndGet()==point){int pid=(int)physical().get("pid");assertThat(result(job(()->jdbc.queryForObject("select pg_terminate_backend(?)",Boolean.class,pid)))).isEqualTo(true);}return null;}).when(store).flush();
        assertThatThrownBy(()->service.terminateEmployee(end(e))).isInstanceOf(RuntimeException.class).isNotInstanceOf(HrFailure.class);unchanged(e,before,rows);
        System.out.println("H34-04 EVIDENCE T25 pg_terminate_backend after flush"+point+"; independent DB full rollback");
    }
    @Test void am02_transferPreservesNonzeroGenerationThenTerminationIncrementsExactlyOnce() {
        var e=create("A");jdbc.update("update hr_employee set auth_revocation_generation=7 where id=?",e.employee().id());
        var bClock=Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"),ZoneOffset.UTC);
        new EmployeeAssignmentService(store,assignments,guard,manager,bClock,30).changeAssignment(request(e.employee().id(),0L,today.minusDays(1),site2,department,position,"promotion"));
        assertThat(employee(e.employee().id()).get("auth_revocation_generation")).isEqualTo(7L);
        service.terminateEmployee(ending(e.employee().id(),1L,today,"r"));assertThat(employee(e.employee().id()).get("version")).isEqualTo(2L);assertThat(employee(e.employee().id()).get("auth_revocation_generation")).isEqualTo(8L);
    }
    @Test void am02_generationOverflowFailsEntireRootAndNegativeDbCheck() {
        var e=create("A");sqlFailure("23514","ck_hr_employee_auth_revocation_generation",()->jdbc.update("update hr_employee set auth_revocation_generation=-1 where id=?",e.employee().id()));
        jdbc.update("update hr_employee set auth_revocation_generation=? where id=?",Long.MAX_VALUE,e.employee().id());
        var before=employee(e.employee().id());var rows=history(e.employee().id());
        assertThatThrownBy(()->service.terminateEmployee(end(e))).isInstanceOf(ArithmeticException.class);unchanged(e,before,rows);
        sqlFailure("22003",null,()->jdbc.update("update hr_employee set auth_revocation_generation=auth_revocation_generation+1 where id=?",e.employee().id()));unchanged(e,before,rows);
        System.out.println("H34-04 EVIDENCE AM-02 Java exact overflow aborts complete Root; PG bigint overflow22003 / negative CHECK23514 verified separately");
    }
    @Test void t29_r2_reasonCheckIsolatedFromDatesAndVarcharLength() {
        var e=create("A");
        for(String reason:Arrays.asList(null,""," ","\u00a0","\u3000","x\t","x\n","x\u0085"," x","x "))
            sqlFailure("23514","ck_hr_employee_termination_reason",()->jdbc.update("update hr_employee set employment_status='TERMINATED',termination_effective_on=?,termination_reason=? where id=?",today,reason,e.employee().id()));
        for(String state:List.of("EMPLOYED","ON_LEAVE")) {
            sqlFailure("23514","ck_hr_employee_termination_reason",()->jdbc.update("update hr_employee set employment_status=?,termination_reason='not allowed' where id=?",state,e.employee().id()));
            jdbc.update("update hr_employee set employment_status=?,termination_reason=null where id=?",state,e.employee().id());
        }
        sqlFailure("22001",null,()->jdbc.update("update hr_employee set employment_status='TERMINATED',termination_effective_on=?,termination_reason=? where id=?",today,"x".repeat(501),e.employee().id()));
        for(String valid:List.of("x","x".repeat(500),"😀".repeat(500))) {
            jdbc.update("update hr_employee set employment_status='TERMINATED',termination_effective_on=?,termination_reason=? where id=?",today,valid,e.employee().id());
            assertThat(jdbc.queryForObject("select char_length(termination_reason) from hr_employee where id=?",Integer.class,e.employee().id())).isEqualTo(valid.codePointCount(0,valid.length()));
        }
        System.out.println("H34-04 EVIDENCE R2 T29 isolated reason CHECK23514+ck_hr_employee_termination_reason; separate varchar501 failure22001; valid1/500/emoji500");
    }
    @Test void t15_maximumSupportedDateRemainsExclusiveWithoutAddingADay() {
        var end=LocalDate.of(9999,12,31);var begin=end.minusDays(1);
        var registrar=new EmployeeRegistrationService(store,guard,manager,Clock.fixed(Instant.parse("9999-12-30T03:00:00Z"),ZoneOffset.UTC));
        var e=registrar.createEmployee(new CreateEmployee(company,"MAX","fixture",begin,site,department,position,"r"));
        when(clock.instant()).thenReturn(Instant.parse("9999-12-31T03:00:00Z"));
        var r=service.terminateEmployee(ending(e.employee().id(),0L,end,"last supported day"));
        assertThat(r.terminationEffectiveOn()).isEqualTo(end);assertThat(queries.findAssignmentAt(company,e.employee().id(),begin)).isPresent();
        assertThat(queries.findAssignmentAt(company,e.employee().id(),end)).isEmpty();
    }

}
