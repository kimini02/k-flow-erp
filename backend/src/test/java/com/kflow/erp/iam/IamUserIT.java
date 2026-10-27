package com.kflow.erp.iam;

import com.kflow.erp.KFlowErpApplication;
import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.domain.User;
import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.application.*;
import com.kflow.erp.hr.domain.Employee;
import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.application.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
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
import static com.kflow.erp.iam.api.IamFailure.Code.*;

@Testcontainers @SpringBootTest(classes=KFlowErpApplication.class)
@Import(IamUserIT.ProbeConfiguration.class) @Timeout(60)
class IamUserIT {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16")
            .withInitScript("org03-probe.sql").withCommand("postgres","-c","log_statement=all","-c","log_line_prefix=%p %x ");
    @TestConfiguration @EntityScan(basePackageClasses={KFlowErpApplication.class,ManagedProbe.class}) static class ProbeConfiguration{}
    @Autowired UserService service;
    @Autowired UserQueryService queries;
    @Autowired EmployeeAssignmentService transfers;
    @Autowired EmployeeTerminationService terminations;
    @Autowired OrganizationAssignmentGuard orgGuard;
    @Autowired OrganizationStateService states;
    @MockitoSpyBean OrganizationService organization;
    @MockitoSpyBean EmployeeAccountLinkGuard guard;
    @MockitoSpyBean HrAccountLinkStore links;
    @MockitoSpyBean HrAssignmentStore hrLocks;
    @MockitoSpyBean HrStore hrStore;
    @MockitoSpyBean IamStore store;
    @MockitoSpyBean OrganizationLockStore orgLocks;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @MockitoBean(name="hrClock") Clock hrClock;
    @MockitoBean(name="iamClock") Clock clock;
    final LocalDate joined=LocalDate.of(2026,10,1),today=LocalDate.of(2026,10,11);
    final Instant now=Instant.parse("2026-10-11T03:00:00Z");
    UUID company,site,department,position,site2,department2,position2;
    ExecutorService pool;List<CountDownLatch> gates;
    @BeforeEach void setup(){
        when(clock.instant()).thenReturn(now);when(hrClock.instant()).thenReturn(now);
        jdbc.update("delete from iam_user");jdbc.update("delete from hr_employee_assignment");jdbc.update("delete from hr_employee");jdbc.update("delete from org03_probe.org03_test_probe");
        for(String t:List.of("site","department","position"))jdbc.update("delete from organization_"+t);
        company=organization.getCompany().companyId();
        site=organization.registerSite("S","site").siteId();site2=organization.registerSite("S2","other").siteId();
        department=organization.registerDepartment("D","department").departmentId();department2=organization.registerDepartment("D2","other").departmentId();
        position=organization.registerPosition("P","position").positionId();position2=organization.registerPosition("P2","other").positionId();
        pool=Executors.newFixedThreadPool(4);gates=new ArrayList<>();
    }
    @AfterEach void cleanup()throws Exception{gates.forEach(CountDownLatch::countDown);pool.shutdownNow();assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
    EmployeeCreated employee(String no){
        var registration=new EmployeeRegistrationService(hrStore,orgGuard,manager,Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"),ZoneOffset.UTC));
        var e=registration.createEmployee(new CreateEmployee(company,no,"synthetic employee",joined,site,department,position,"original reason"));
        assertThat(row(e.employee().id()).get("version")).isEqualTo(0L);assertThat(history(e.employee().id()).getFirst().get("version")).isEqualTo(0L);
        System.out.println("I1 R2 baseline: registration Root committed at d=2026-10-01, Employee="+e.employee().id()+" v0/tail0; separate competition Clock d=2026-10-11");return e;
    }
    CreateUser create(EmployeeCreated e,String key){return new CreateUser(company,e.employee().id(),key,"  creation reason  ");}
    ChangeUserStatus change(UserView u,long v){return new ChangeUserStatus(company,u.userId(),v,"  state reason  ");}
    TerminateEmployee end(EmployeeCreated e){return new TerminateEmployee(company,e.employee().id(),0L,today,"termination reason");}
    ChangeAssignment transfer(EmployeeCreated e){return new ChangeAssignment(company,e.employee().id(),0L,today,site2,department2,position2,"transfer reason");}
    void failure(IamFailure.Code code,Runnable r){assertThatThrownBy(r::run).isInstanceOfSatisfying(IamFailure.class,e->assertThat(e.code()).isEqualTo(code));}
    void hrFailure(HrFailure.Code code,Runnable r){assertThatThrownBy(r::run).isInstanceOfSatisfying(HrFailure.class,e->assertThat(e.code()).isEqualTo(code));}
    <T>T tx(Supplier<T> a){return new TransactionTemplate(manager).execute(s->a.get());}
    CountDownLatch gate(){var g=new CountDownLatch(1);gates.add(g);return g;}
    void await(CountDownLatch g){try{assertThat(g.await(12,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    Future<Object> job(String name,Supplier<?> a){return pool.submit(()->{String old=Thread.currentThread().getName();Thread.currentThread().setName(name);try{return a.get();}catch(RuntimeException e){return e;}finally{Thread.currentThread().setName(old);}});}
    Object result(Future<Object> f){try{return f.get(15,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}}
    boolean thread(String name){return Thread.currentThread().getName().equals(name);}
    Map<String,Object> physical(){return em.unwrap(Session.class).doReturningWork(c->{try(var s=c.createStatement();var r=s.executeQuery("select pg_backend_pid(),pg_current_xact_id()::text")){r.next();return Map.of("pid",r.getInt(1),"xid",r.getString(2));}});}
    String sql(Map<String,Object> id){String key=id.get("pid")+" "+id.get("xid")+" ";return postgres.getLogs().lines().filter(l->l.contains(key)).reduce("",(a,b)->a+b+"\n");}
    void blocked(String query,Map<String,Object> blocker){
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
        while(System.nanoTime()<until){
            var rows=jdbc.queryForList("select pid,pg_blocking_pids(pid)::text blockers,query from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and cardinality(pg_blocking_pids(pid))>0 and query ilike ?","%"+query+"%");
            for(var r:rows)if(blocker==null||Arrays.asList(r.get("blockers").toString().replace("{","").replace("}","").split(",")).contains(blocker.get("pid").toString())){
                assertThat(r.get("pid")).isNotEqualTo(blocker==null?null:blocker.get("pid"));System.out.println("I1 actual PG blocker "+r);return;
            }
            try{Thread.sleep(15);}catch(InterruptedException e){throw new AssertionError(e);}
        }fail("No actual PostgreSQL blocker: "+query);
    }
    Map<String,Object> row(UUID id){return jdbc.queryForMap("select * from hr_employee where id=?",id);}
    List<Map<String,Object>> history(UUID id){return jdbc.queryForList("select * from hr_employee_assignment where employee_id=? order by effective_from,id",id);}
    Map<String,Object> user(UUID id){return jdbc.queryForMap("select * from iam_user where id=?",id);}
    int count(){return jdbc.queryForObject("select count(*) from iam_user",Integer.class);}
    PSQLException pg(Throwable f){for(Throwable c=f;c!=null;c=c.getCause())if(c instanceof PSQLException p)return p;throw new AssertionError("Expected PostgreSQL cause",f);}
    void sqlFailure(String state,String constraint,Runnable r){var error=catchThrowable(r::run);var p=pg(error);assertThat(p.getSQLState()).isEqualTo(state);if(constraint!=null)assertThat(p.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
        assertThat(store.translate((RuntimeException)error)).isSameAs(error);System.out.println("I1 isolated SQLSTATE="+state+" constraint="+constraint+" remains technical");}
    void unchanged(EmployeeCreated e,Map<String,Object> before,List<Map<String,Object>> h){assertThat(row(e.employee().id())).isEqualTo(before);assertThat(history(e.employee().id())).isEqualTo(h);}

    @Test void t01_t02_t16_creationSqlIdentityAndSamePhysicalRoot(){
        var e=employee("A");var before=row(e.employee().id());var h=history(e.employee().id());var ids=new ArrayList<Map<String,Object>>();
        doAnswer(c->{c.callRealMethod();ids.add(physical());return null;}).when(store).requireWritableTransaction();
        doAnswer(c->{var value=c.callRealMethod();ids.add(physical());assertThat(count()).isZero();return value;}).when(links).lockForLink(any());
        doAnswer(c->{c.callRealMethod();ids.add(physical());return null;}).when(store).flush();
        var u=service.createUser(create(e,"  MiN.A  "));
        assertThat(u.userId().version()).isEqualTo(4);assertThat(u.userId()).isNotEqualTo(e.employee().id());assertThat(u.loginKey()).isEqualTo("min.a");assertThat(u.accountStatus()).isEqualTo("DISABLED");assertThat(u.version()).isZero();assertThat(u.creationReason()).isEqualTo("creation reason");
        assertThat(u.lastStatusReason()).isNull();assertThat(u.statusChangedAt()).isNull();assertThat(u.createdAt()).isEqualTo(now);assertThat(u.updatedAt()).isEqualTo(now);
        assertThat(user(u.userId()).get("auth_revocation_generation")).isEqualTo(0L);unchanged(e,before,h);
        assertThat(queries.findUser(company,u.userId())).contains(u);assertThat(queries.findUserByEmployee(company,e.employee().id())).contains(u);
        assertThat(ids).hasSize(3).allMatch(ids.getFirst()::equals);String sql=sql(ids.getFirst());assertThat(sql).contains("FOR SHARE","insert into iam_user");assertThat(sql.indexOf("FOR SHARE")).isLessThan(sql.indexOf("insert into iam_user"));assertThat(sql).doesNotContain("update hr_employee","update hr_employee_assignment");
        System.out.println("I1 T01/T14/T16: same PID/XID "+ids+"; HR unchanged; initial v0/g0\n"+sql);
        clearInvocations(links,orgLocks);service.enableUser(change(u,0));service.disableUser(change(u,1));
        var current=queries.findUser(company,u.userId()).orElseThrow();assertThat(current.userId()).isEqualTo(u.userId());assertThat(current.employeeId()).isEqualTo(e.employee().id());assertThat(current.loginKey()).isEqualTo(u.loginKey());assertThat(current.creationReason()).isEqualTo(u.creationReason());
        verify(links,never()).lockForLink(any());verify(orgLocks,never()).lockForAssignment(any(),any(),any());
        reset(store,links);failure(EMPLOYEE_ACCOUNT_CONFLICT,()->service.createUser(create(e,"different.key")));assertThat(count()).isEqualTo(1);
    }
    @ParameterizedTest @CsvSource({"true,true","true,false","false,true","false,false"})
    void t04_t05_twoShareGuardsThenUniqueWait(boolean sameEmployee,boolean firstCommits){
        var a=employee("A");var b=sameEmployee?a:employee("B");var both=new CountDownLatch(2);var inserted=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();
        doAnswer(c->{ids.put(Thread.currentThread().getName(),physical());both.countDown();await(both);if(thread("B"))await(inserted);return c.callRealMethod();}).when(store).add(any());
        doAnswer(c->{c.callRealMethod();if(thread("A")){inserted.countDown();await(release);if(!firstCommits)throw new IllegalStateException("first insert abort");}return null;}).when(store).flush();
        var fa=job("A",()->service.createUser(create(a,"same.key")));var fb=job("B",()->service.createUser(create(b,sameEmployee?"other.key":"SAME.KEY")));
        await(inserted);assertThat(ids).hasSize(2);assertThat(ids.get("A").get("pid")).isNotEqualTo(ids.get("B").get("pid"));assertThat(ids.get("A").get("xid")).isNotEqualTo(ids.get("B").get("xid"));blocked("insert into iam_user",ids.get("A"));assertThat(fb.isDone()).isFalse();release.countDown();
        Object ra=result(fa),rb=result(fb);if(firstCommits){assertThat(ra).isInstanceOf(UserView.class);assertThat(rb).isInstanceOfSatisfying(IamFailure.class,f->assertThat(f.code()).isEqualTo(sameEmployee?EMPLOYEE_ACCOUNT_CONFLICT:LOGIN_KEY_CONFLICT));var err=pg((Throwable)rb);assertThat(err.getSQLState()).isEqualTo("23505");assertThat(err.getServerErrorMessage().getConstraint()).isEqualTo(sameEmployee?"uq_iam_user_employee":"uq_iam_user_login_key");}
        else {assertThat(ra).isInstanceOf(RuntimeException.class);assertThat(rb).isInstanceOf(UserView.class);}assertThat(count()).isEqualTo(1);
        System.out.println("I1 T04/T05 both actual Guards reached protected region BEFORE insert; independent "+ids+" firstCommit="+firstCommits+" sameEmployee="+sameEmployee+" User rows="+jdbc.queryForList("select * from iam_user"));
    }
    @ParameterizedTest @CsvSource({"true,true,true","true,true,false","true,false,true","true,false,false","false,true,true","false,true,false","false,false,true","false,false,false"})
    void t09_t12_t17_realCreateVsTerminationOrTransfer(boolean terminate,boolean iamFirst,boolean firstCommits){
        var e=employee("A");var before=row(e.employee().id());var h=history(e.employee().id());var held=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();var flushes=new AtomicInteger();
        doAnswer(c->{var v=c.callRealMethod();ids.put("IAM",physical());return v;}).when(links).lockForLink(any());
        doAnswer(c->{var v=c.callRealMethod();ids.put("HR",physical());return v;}).when(hrLocks).lockEmployee(any(),any());
        if(iamFirst)doAnswer(c->{c.callRealMethod();held.countDown();await(release);if(!firstCommits)throw new IllegalStateException("IAM abort");return null;}).when(store).flush();
        else doAnswer(c->{c.callRealMethod();if(flushes.incrementAndGet()==2){held.countDown();await(release);if(!firstCommits)throw new IllegalStateException("HR abort");}return null;}).when(hrStore).flush();
        Supplier<?> iam=()->service.createUser(create(e,"account"));Supplier<?> hr=()->terminate?terminations.terminateEmployee(end(e)):transfers.changeAssignment(transfer(e));
        var first=job(iamFirst?"IAM":"HR",iamFirst?iam:hr);await(held);var second=job(iamFirst?"HR":"IAM",iamFirst?hr:iam);
        blocked(iamFirst?"FOR UPDATE":"FOR SHARE",ids.get(iamFirst?"IAM":"HR"));assertThat(second.isDone()).isFalse();release.countDown();Object r1=result(first),r2=result(second);
        if(!firstCommits)assertThat(r1).isInstanceOf(RuntimeException.class);else assertThat(r1).isNotInstanceOf(Throwable.class);
        boolean rejected=terminate&&!iamFirst&&firstCommits;
        if(rejected)assertThat(r2).isInstanceOfSatisfying(HrFailure.class,f->assertThat(f.code()).isEqualTo(HrFailure.Code.EMPLOYEE_TERMINATED));else assertThat(r2).isNotInstanceOf(Throwable.class);
        boolean hrCommitted=iamFirst||firstCommits;boolean iamCommitted=iamFirst?firstCommits:!rejected;
        assertThat(count()).isEqualTo(iamCommitted?1:0);
        if(!hrCommitted)unchanged(e,before,h);else {
            assertThat(row(e.employee().id()).get("version")).isEqualTo(1L);assertThat(row(e.employee().id()).get("employment_status")).isEqualTo(terminate?"TERMINATED":"EMPLOYED");
            assertThat(row(e.employee().id()).get("auth_revocation_generation")).isEqualTo(terminate?1L:0L);
            var last=history(e.employee().id());assertThat(last).hasSize(terminate?1:2);assertThat(last.getFirst().get("effective_to")).isEqualTo(java.sql.Date.valueOf(today));assertThat(last.getFirst().get("version")).isEqualTo(1L);assertThat(last.getFirst().get("reason")).isEqualTo("original reason");
            if(!terminate){assertThat(last.getLast().get("version")).isEqualTo(0L);assertThat(last.getLast().get("effective_from")).isEqualTo(java.sql.Date.valueOf(today));assertThat(last.getLast().get("effective_to")).isNull();assertThat(row(e.employee().id()).get("updated_at")).isEqualTo(before.get("updated_at"));}
        }
        assertThat(ids.get("HR").get("pid")).isNotEqualTo(ids.get("IAM").get("pid"));assertThat(ids.get("HR").get("xid")).isNotEqualTo(ids.get("IAM").get("xid"));
        System.out.println("I1 T09-12/T17 terminate="+terminate+" IAM-first="+iamFirst+" firstCommit="+firstCommits+" roots="+ids+" HR="+row(e.employee().id())+" history="+history(e.employee().id())+" User count="+count());
    }
    @Test void t17_shareBlocksBareNonKeyUpdateUntilRootRollback(){
        var e=employee("A");var before=row(e.employee().id());var held=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();
        var a=job("share",()->tx(()->{ids.put("share",physical());guard.requireLinkable(company,e.employee().id(),today);held.countDown();await(release);throw new IllegalStateException("rollback share root");}));
        await(held);var b=job("bare",()->tx(()->{ids.put("bare",physical());jdbc.update("update hr_employee set version=version+1 where id=?",e.employee().id());throw new IllegalStateException("rollback isolated bare writer");}));
        blocked("update hr_employee set version",ids.get("share"));assertThat(b.isDone()).isFalse();release.countDown();assertThat(result(a)).isInstanceOf(RuntimeException.class);assertThat(result(b)).isInstanceOf(RuntimeException.class);
        String share=sql(ids.get("share")),bare=sql(ids.get("bare"));assertThat(share).contains("FOR SHARE");assertThat(bare).doesNotContain("FOR UPDATE","FOR SHARE").contains("update hr_employee set version");assertThat(row(e.employee().id())).isEqualTo(before);
        System.out.println("I1 T17 separate NO-prelock non-key UPDATE blocked by actual SHARE, both rollback; roots="+ids+"\n"+share+bare);
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t17_inactiveOrganizationDoesNotBlockCreate(OrganizationTarget kind){
        var e=employee("A");switch(kind){case SITE->states.deactivateSite(company,site,0L,"inactive");case DEPARTMENT->states.deactivateDepartment(company,department,0L,"inactive");case POSITION->states.deactivatePosition(company,position,0L,"inactive");}
        var before=row(e.employee().id());var h=history(e.employee().id());clearInvocations(orgLocks);
        service.createUser(create(e,"account"));verify(orgLocks,never()).lockForAssignment(any(),any(),any());unchanged(e,before,h);
        var change=new ChangeAssignment(company,e.employee().id(),0L,today,kind==OrganizationTarget.SITE?site:site2,kind==OrganizationTarget.DEPARTMENT?department:department2,kind==OrganizationTarget.POSITION?position:position2,"transfer");
        assertThatThrownBy(()->transfers.changeAssignment(change)).isInstanceOf(OrganizationFailure.class);unchanged(e,before,h);
    }
    @Test void t06_t07_t08_guardErrorsAreDistinctAndNoRows(){
        var e=employee("A");
        hrFailure(HrFailure.Code.EMPLOYEE_NOT_FOUND,()->service.createUser(new CreateUser(company,UUID.randomUUID(),"missing","reason")));
        hrFailure(HrFailure.Code.EMPLOYEE_NOT_FOUND,()->service.createUser(new CreateUser(company,site,"not.employee","reason")));
        assertThatThrownBy(()->service.createUser(new CreateUser(UUID.randomUUID(),e.employee().id(),"wrong.company","reason"))).isInstanceOfSatisfying(OrganizationFailure.class,f->assertThat(f.code()).isEqualTo(OrganizationFailure.Code.COMPANY_NOT_FOUND));
        hrFailure(HrFailure.Code.EMPLOYEE_COMPANY_MISMATCH,()->tx(()->{guard.requireLinkable(UUID.randomUUID(),e.employee().id(),today);return null;}));
        jdbc.update("update hr_employee set employment_status='ON_LEAVE' where id=?",e.employee().id());
        hrFailure(HrFailure.Code.ON_LEAVE_ACCOUNT_LINK_UNSUPPORTED,()->service.createUser(create(e,"leave")));assertThat(row(e.employee().id()).get("employment_status")).isEqualTo("ON_LEAVE");
        jdbc.update("update hr_employee set employment_status='EMPLOYED' where id=?",e.employee().id());
        hrFailure(HrFailure.Code.EMPLOYMENT_DATE_UNSUPPORTED,()->tx(()->{guard.requireLinkable(company,e.employee().id(),joined.minusDays(1));return null;}));
        terminations.terminateEmployee(end(e));hrFailure(HrFailure.Code.EMPLOYEE_TERMINATED,()->service.createUser(create(e,"ended")));assertThat(count()).isZero();
    }
    @Test void t13_guardUsesLatestScalarDespiteManagedEmployee(){
        var e=employee("A");var loaded=gate();var committed=gate();var id=new AtomicReference<Map<String,Object>>();
        var a=job("stale",()->tx(()->{var old=em.find(Employee.class,e.employee().id());assertThat(old.identity().employmentStatus()).isEqualTo("EMPLOYED");id.set(physical());loaded.countDown();await(committed);
            assertThat(old.identity().employmentStatus()).isEqualTo("EMPLOYED");try{guard.requireLinkable(company,e.employee().id(),today);fail("stale employee authorized");}finally{assertThat(em.contains(old)).isTrue();assertThat(old.identity().employmentStatus()).isEqualTo("EMPLOYED");}return null;}));
        await(loaded);terminations.terminateEmployee(end(e));committed.countDown();assertThat(result(a)).isInstanceOfSatisfying(HrFailure.class,f->assertThat(f.code()).isEqualTo(HrFailure.Code.EMPLOYEE_TERMINATED));assertThat(count()).isZero();System.out.println("I1 T13 stale managed Employee retained; latest locking scalar refused; "+id.get());
    }
    @Test void t15_caughtGuardFailureStillRollsBackConsumer(){
        var e=employee("A");terminations.terminateEmployee(end(e));
        assertThatThrownBy(()->tx(()->{try{guard.requireLinkable(company,e.employee().id(),today);}catch(HrFailure f){assertThat(f.code()).isEqualTo(HrFailure.Code.EMPLOYEE_TERMINATED);}
            store.add(new User(company,e.employee().id(),"probe","test-only forbidden continuation",now));store.flush();return null;})).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(count()).isZero();System.out.println("I1 T15 caught Guard rejection marks root rollback-only; even test-only User INSERT+flush cannot commit");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void t16_guardDoesNotFlushDetachOrClearProbe(boolean abort){
        var e=employee("A");UUID probe=UUID.randomUUID();jdbc.update("insert into org03_probe.org03_test_probe(id,label) values(?,'before')",probe);
        Runnable action=()->tx(()->{var p=em.find(ManagedProbe.class,probe);var old=em.find(Employee.class,e.employee().id());var mode=em.getFlushMode();
            guard.requireLinkable(company,e.employee().id(),today);assertThat(em.contains(p)).isTrue();assertThat(em.contains(old)).isTrue();assertThat(em.getFlushMode()).isEqualTo(mode);
            p.label("after");guard.requireLinkable(company,e.employee().id(),today);assertThat(jdbc.queryForObject("select label from org03_probe.org03_test_probe where id=?",String.class,probe)).isEqualTo("before");
            store.add(new User(company,e.employee().id(),"probe.user","fixture",now));store.flush();if(abort)throw new IllegalStateException("probe abort");return null;});
        if(abort)assertThatThrownBy(action::run).isInstanceOf(RuntimeException.class);else action.run();
        assertThat(jdbc.queryForObject("select label from org03_probe.org03_test_probe where id=?",String.class,probe)).isEqualTo(abort?"before":"after");assertThat(count()).isEqualTo(abort?0:1);
    }
    @ParameterizedTest @ValueSource(strings={"absent","readOnly","repeatable","wrongManager","nullRC","nullRR","nullReadOnly"})
    void t14_guardPhysicalTransactionBoundaries(String mode){
        var e=employee("A");
        if(mode.equals("absent")){hrFailure(HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION,()->guard.requireLinkable(company,e.employee().id(),today));return;}
        PlatformTransactionManager m=mode.equals("wrongManager")?new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()):manager;
        var t=new TransactionTemplate(m);t.setIsolationLevel(mode.equals("repeatable")||mode.equals("nullRR")?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED);
        t.setReadOnly(mode.equals("readOnly")||mode.equals("nullReadOnly"));
        Runnable action=()->t.executeWithoutResult(s->{Integer old=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();boolean readOnly=TransactionSynchronizationManager.isCurrentTransactionReadOnly();if(mode.startsWith("null")){TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);}
            try{guard.requireLinkable(company,e.employee().id(),today);}finally{TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(old);TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);}});
        if(mode.equals("nullRC"))action.run();else hrFailure(HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION,action);assertThat(count()).isZero();
    }
    @Test void t14_t29_iamRejectsAmbientAndWrongManagerBeforeGuard(){
        var e=employee("A");clearInvocations(links,organization);
        failure(TRANSACTION_BOUNDARY_VIOLATION,()->tx(()->service.createUser(create(e,"ambient"))));
        var wrong=new UserService(store,guard,organization,new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()),clock,30);
        failure(TRANSACTION_BOUNDARY_VIOLATION,()->wrong.createUser(create(e,"wrong")));verify(links,never()).lockForLink(any());verify(organization,never()).getCompany();assertThat(count()).isZero();
        // Negative split-connection harness: an independently committed SQL write is NOT a supported IAM root.
        tx(()->{guard.requireLinkable(company,e.employee().id(),today);var owner=physical();try(var c=jdbc.getDataSource().getConnection();var s=c.createStatement();var r=s.executeQuery("select pg_backend_pid(),pg_current_xact_id()::text")){r.next();assertThat(r.getInt(1)).isNotEqualTo(owner.get("pid"));assertThat(r.getString(2)).isNotEqualTo(owner.get("xid"));}catch(Exception x){throw new AssertionError(x);}return null;});
    }
    @Test void t29_negativePersistBeforeGuardIsDetectedAndRolledBack(){
        var e=employee("A");var id=new AtomicReference<Map<String,Object>>();
        var root=new TransactionTemplate(manager);root.executeWithoutResult(tx->{
            store.add(new User(company,e.employee().id(),"bad.order","test-only negative fixture",now));store.flush();
            guard.requireLinkable(company,e.employee().id(),today);id.set(physical());tx.setRollbackOnly();
        });
        String captured=sql(id.get());
        assertThatThrownBy(()->assertThat(captured.indexOf("FOR SHARE")).isLessThan(captured.indexOf("insert into iam_user"))).isInstanceOf(AssertionError.class);
        assertThat(count()).isZero();System.out.println("I1 T29 negative test-only persist-before-Guard violates actual SQL-order assertion; Root rolled back; no production bypass method\n"+captured);
    }
    @Test void t18_am01_singleDirtyUpdateAndNoOpGeneration(){
        var e=employee("A");var u=service.createUser(create(e,"account"));var before=row(e.employee().id());var h=history(e.employee().id());var ids=new ArrayList<Map<String,Object>>();clearInvocations(guard,hrLocks);
        var sameTx=new ArrayList<Map<String,Object>>();
        doAnswer(c->{c.callRealMethod();ids.add(physical());sameTx.add(user(u.userId()));return null;}).when(store).flush();
        var enabled=service.enableUser(change(u,0));assertThat(enabled.version()).isEqualTo(1);assertThat(user(u.userId()).get("auth_revocation_generation")).isEqualTo(0L);
        var disabled=service.disableUser(change(u,1));assertThat(disabled.version()).isEqualTo(2);assertThat(user(u.userId()).get("auth_revocation_generation")).isEqualTo(1L);
        assertThat(sameTx).hasSize(2);assertThat(sameTx.getFirst().get("version")).isEqualTo(enabled.version());assertThat(sameTx.getFirst().get("auth_revocation_generation")).isEqualTo(0L);assertThat(sameTx.getLast().get("version")).isEqualTo(disabled.version());assertThat(sameTx.getLast()).isEqualTo(user(u.userId()));
        System.out.println("I1 T18 flush-time SAME physical Tx DB="+sameTx+"; returned DTO versions="+enabled.version()+","+disabled.version()+"; committed DB="+user(u.userId()));
        assertThat(disabled.lastStatusReason()).isEqualTo("state reason");assertThat(disabled.statusChangedAt()).isEqualTo(now);assertThat(disabled.creationReason()).isEqualTo(u.creationReason());assertThat(queries.findUser(company,u.userId())).contains(disabled);
        for(var id:ids){String sql=sql(id);var updates=sql.lines().filter(l->l.contains("update iam_user set")).toList();assertThat(updates).hasSize(1);assertThat(updates.getFirst()).contains("account_status","auth_revocation_generation","last_status_reason","version");assertThat(sql).doesNotContain("hr_employee","FOR SHARE");System.out.println("I1 T18 AM01 one dirty UPDATE v+1, disable g+1 / enable g unchanged "+id+"\n"+sql);}
        verify(guard,never()).requireLinkable(any(),any(),any());verify(hrLocks,never()).lockEmployee(any(),any());unchanged(e,before,h);
        var saved=user(u.userId());when(clock.instant()).thenReturn(now.plusSeconds(50));clearInvocations(store);
        assertThat(service.disableUser(new ChangeUserStatus(company,u.userId(),2L,"other valid reason"))).isEqualTo(disabled);assertThat(user(u.userId())).isEqualTo(saved);verify(store,never()).flush();
        failure(VERSION_CONFLICT,()->service.disableUser(change(u,1)));failure(INVALID_INPUT,()->service.disableUser(new ChangeUserStatus(company,u.userId(),2L," ")));
        assertThat(service.enableUser(change(u,2)).version()).isEqualTo(3);assertThat(user(u.userId()).get("auth_revocation_generation")).isEqualTo(1L);unchanged(e,before,h);
    }
    @ParameterizedTest @CsvSource({"enable","disable","twoNoops","noopFirst","changeFirst"})
    void t19_t20_userStateCompetition(String kind){
        var e=employee("A");var u=service.createUser(create(e,"account"));if(kind.equals("disable"))u=service.enableUser(change(u,0));final var current=u;
        var before=user(u.userId());var held=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();
        doAnswer(c->{var v=c.callRealMethod();ids.put(Thread.currentThread().getName(),physical());if(thread("A")){held.countDown();await(release);}return v;}).when(store).lockUser(any(),any());
        boolean aNoop=kind.equals("twoNoops")||kind.equals("noopFirst");boolean bNoop=kind.equals("twoNoops")||kind.equals("changeFirst");
        Supplier<UserView> ca=()->aNoop?service.disableUser(change(current,current.version())):kind.equals("disable")?service.disableUser(change(current,current.version())):service.enableUser(change(current,current.version()));
        Supplier<UserView> cb=()->bNoop?service.disableUser(change(current,current.version())):kind.equals("disable")?service.disableUser(change(current,current.version())):service.enableUser(change(current,current.version()));
        var a=job("A",ca);await(held);var b=job("B",cb);blocked("iam_user",ids.get("A"));assertThat(b.isDone()).isFalse();release.countDown();assertThat(result(a)).isInstanceOf(UserView.class);var rb=result(b);
        if(aNoop)assertThat(rb).isInstanceOf(UserView.class);else assertThat(rb).isInstanceOfSatisfying(IamFailure.class,f->assertThat(f.code()).isEqualTo(VERSION_CONFLICT));
        if(kind.equals("twoNoops")){assertThat(user(u.userId())).isEqualTo(before);for(var id:ids.values())assertThat(sql(id)).doesNotContain("update iam_user");}else assertThat(user(u.userId()).get("version")).isEqualTo(current.version()+1);
        assertThat(ids.get("A").get("xid")).isNotEqualTo(ids.get("B").get("xid"));System.out.println("I1 T19/T20 "+kind+" "+ids+" final="+user(u.userId()));
    }
    @ParameterizedTest @CsvSource({"TERMINATED,true","TERMINATED,false","ON_LEAVE,true","ON_LEAVE,false"})
    void t21A_existingTerminatedOrLeaveUserManagement(String state,boolean enable){
        var e=employee("A");var u=service.createUser(create(e,"account"));if(!enable)u=service.enableUser(change(u,0));
        if(state.equals("TERMINATED"))terminations.terminateEmployee(end(e));else jdbc.update("update hr_employee set employment_status='ON_LEAVE' where id=?",e.employee().id());
        var before=row(e.employee().id());var h=history(e.employee().id());long version=u.version();clearInvocations(guard,orgLocks,hrLocks);
        var output=enable?service.enableUser(change(u,version)):service.disableUser(change(u,version));assertThat(output.version()).isEqualTo(version+1);assertThat(output.accountStatus()).isEqualTo(enable?"ENABLED":"DISABLED");unchanged(e,before,h);
        verify(guard,never()).requireLinkable(any(),any(),any());verify(hrLocks,never()).lockEmployee(any(),any());verify(orgLocks,never()).lockForAssignment(any(),any(),any());
    }
    @ParameterizedTest @CsvSource({"true,true,true","true,true,false","true,false,true","true,false,false","false,true,true","false,true,false","false,false,true","false,false,false"})
    void t21B_t25_independentUserChangeAndActualTermination(boolean enable,boolean iamFirst,boolean firstCommits){
        var e=employee("A");var created=service.createUser(create(e,"account"));final var u=enable?created:service.enableUser(change(created,0));
        var beforeUser=user(u.userId());var beforeEmployee=row(e.employee().id());var beforeHistory=history(e.employee().id());var held=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();var n=new AtomicInteger();clearInvocations(guard);
        doAnswer(c->{c.callRealMethod();ids.put("IAM",physical());if(iamFirst){held.countDown();await(release);if(!firstCommits)throw new IllegalStateException("IAM rollback");}return null;}).when(store).flush();
        doAnswer(c->{c.callRealMethod();if(n.incrementAndGet()==2){ids.put("HR",physical());if(!iamFirst){held.countDown();await(release);if(!firstCommits)throw new IllegalStateException("HR rollback");}}return null;}).when(hrStore).flush();
        Supplier<?> iam=()->enable?service.enableUser(change(u,u.version())):service.disableUser(change(u,u.version()));Supplier<?> hr=()->terminations.terminateEmployee(end(e));
        var first=job(iamFirst?"IAM":"HR",iamFirst?iam:hr);await(held);var second=job(iamFirst?"HR":"IAM",iamFirst?hr:iam);
        // Other root MUST commit while the first still holds its actual lock and uncommitted UPDATE.
        assertThat(result(second)).isNotInstanceOf(Throwable.class);assertThat(first.isDone()).isFalse();
        for(var id:ids.values())assertThat(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))",Integer.class,id.get("pid"))).isZero();
        System.out.println("I1 T21B active first lock, second already committed, pg_blocking_pids empty for both PID="+ids);
        release.countDown();var r1=result(first);if(firstCommits)assertThat(r1).isNotInstanceOf(Throwable.class);else assertThat(r1).isInstanceOf(RuntimeException.class);
        if(iamFirst&&!firstCommits)assertThat(user(u.userId())).isEqualTo(beforeUser);else {assertThat(user(u.userId()).get("version")).isEqualTo(u.version()+1);assertThat(user(u.userId()).get("auth_revocation_generation")).isEqualTo(enable?0L:1L);}
        if(!iamFirst&&!firstCommits)unchanged(e,beforeEmployee,beforeHistory);else {assertThat(row(e.employee().id()).get("employment_status")).isEqualTo("TERMINATED");assertThat(row(e.employee().id()).get("version")).isEqualTo(1L);assertThat(row(e.employee().id()).get("auth_revocation_generation")).isEqualTo(1L);assertThat(history(e.employee().id()).getFirst().get("version")).isEqualTo(1L);assertThat(row(e.employee().id()).get("termination_reason")).isEqualTo("termination reason");assertThat(row(e.employee().id()).get("termination_effective_on")).isEqualTo(java.sql.Date.valueOf(today));assertThat(history(e.employee().id()).getFirst().get("effective_to")).isEqualTo(java.sql.Date.valueOf(today));assertThat(history(e.employee().id()).getFirst().get("reason")).isEqualTo("original reason");}
        verify(guard,never()).requireLinkable(any(),any(),any());assertThat(ids.get("IAM").get("pid")).isNotEqualTo(ids.get("HR").get("pid"));assertThat(ids.get("IAM").get("xid")).isNotEqualTo(ids.get("HR").get("xid"));
        String iamSql=sql(ids.get("IAM")),hrSql=sql(ids.get("HR"));
        assertThat(iamSql).contains("update iam_user").doesNotContain("hr_employee");assertThat(hrSql).contains("update hr_employee").doesNotContain("iam_user");
        System.out.println("I1 T21B/T25 SQL+final rows\n"+iamSql+hrSql+"User="+user(u.userId())+" HR="+row(e.employee().id())+" assignments="+history(e.employee().id()));
        System.out.println("I1 T21B/T25 independent real roots "+ids+" enable="+enable+" IAMfirst="+iamFirst+" firstCommit="+firstCommits+"; second committed BEFORE releasing first lock");
    }
    @ParameterizedTest @ValueSource(longs={0,1})
    void t22_freshUserLockRefreshesOnlyTarget(long expected){
        var e=employee("A");var u=service.createUser(create(e,"account"));UUID probe=UUID.randomUUID();jdbc.update("insert into org03_probe.org03_test_probe values(?,'before')",probe);
        var loaded=gate();var go=gate();var stale=new AtomicReference<User>();var unrelated=new AtomicReference<ManagedProbe>();
        when(clock.instant()).thenAnswer(c->{if(thread("stale")){stale.set(em.find(User.class,u.userId()));unrelated.set(em.find(ManagedProbe.class,probe));loaded.countDown();await(go);}return now;});
        doAnswer(c->{var v=c.callRealMethod();if(thread("stale")){assertThat(em.contains(unrelated.get())).isTrue();assertThat(v).isSameAs(stale.get());assertThat(((User)v).facts().version()).isEqualTo(1);unrelated.get().label("after");}return v;}).when(store).lockUser(any(),any());
        var a=job("stale",()->service.disableUser(change(u,expected)));await(loaded);service.enableUser(change(u,0));go.countDown();var r=result(a);
        if(expected==0)assertThat(r).isInstanceOfSatisfying(IamFailure.class,f->assertThat(f.code()).isEqualTo(VERSION_CONFLICT));else assertThat(r).isInstanceOfSatisfying(UserView.class,f->{assertThat(f.version()).isEqualTo(2);assertThat(f.accountStatus()).isEqualTo("DISABLED");});
        assertThat(jdbc.queryForObject("select label from org03_probe.org03_test_probe where id=?",String.class,probe)).isEqualTo(expected==0?"before":"after");
    }
    @Test void t23_reasonAndNullPairChecksIsolatedFromLength(){
        var e=employee("A");var u=service.createUser(create(e,"account"));
        for(String r:Arrays.asList(null,""," ","\u00a0","\u3000","a\n","a\t","a\u0085","\uD800","😀".repeat(501)))
            failure(INVALID_INPUT,()->service.disableUser(new ChangeUserStatus(company,u.userId(),0L,r)));
        for(String r:List.of(""," ","\u00a0","\u3000","a\t","a\n","a\u0085"," a","a ")){
            sqlFailure("23514","ck_iam_user_creation_reason",()->jdbc.update("update iam_user set creation_reason=? where id=?",r,u.userId()));
            sqlFailure("23514","ck_iam_user_status_reason",()->jdbc.update("update iam_user set last_status_reason=?,status_changed_at=now() where id=?",r,u.userId()));
        }
        sqlFailure("23502",null,()->jdbc.update("update iam_user set creation_reason=null where id=?",u.userId()));
        sqlFailure("23514","ck_iam_user_status_reason",()->jdbc.update("update iam_user set last_status_reason='valid',status_changed_at=null where id=?",u.userId()));
        sqlFailure("23514","ck_iam_user_status_reason",()->jdbc.update("update iam_user set last_status_reason=null,status_changed_at=now() where id=?",u.userId()));
        for(String field:List.of("creation_reason","last_status_reason"))sqlFailure("22001",null,()->jdbc.update("update iam_user set "+field+"=?,status_changed_at=now() where id=?","x".repeat(501),u.userId()));
        for(String valid:List.of("x","x".repeat(500),"😀".repeat(500))){
            jdbc.update("update iam_user set creation_reason=?,last_status_reason=?,status_changed_at=now() where id=?",valid,valid,u.userId());
            assertThat(user(u.userId()).get("creation_reason")).isEqualTo(valid);assertThat(user(u.userId()).get("last_status_reason")).isEqualTo(valid);
        }
        System.out.println("I1 T23 actual 23514 named reason CHECKs, paired NULL checks; 23502 required reason; separate501 varchar22001; valid1/500/emoji500");
    }
    @Test void t03_t24_dbConstraintsAndExactErrorTranslation(){
        var a=employee("A");var b=employee("B");var u=service.createUser(create(a,"alpha"));var v=service.createUser(create(b,"bravo"));
        for(String bad:List.of("ABc","ab","@ab","a b"," a.b","a.b ","한글ab","ａｂｃ","ab\n","abc\n"))sqlFailure("23514","ck_iam_user_login_key",()->jdbc.update("update iam_user set login_key=? where id=?",bad,u.userId()));
        sqlFailure("22001",null,()->jdbc.update("update iam_user set login_key=? where id=?","a".repeat(65),u.userId()));
        for(var pair:List.of(new String[]{"account_status='OTHER'","ck_iam_user_status"},new String[]{"version=-1","ck_iam_user_version"},new String[]{"auth_revocation_generation=-1","ck_iam_user_auth_revocation_generation"},new String[]{"created_at='infinity'","ck_iam_user_timestamps"},new String[]{"updated_at='-infinity'","ck_iam_user_timestamps"},new String[]{"last_status_reason='valid',status_changed_at='infinity'","ck_iam_user_timestamps"}))
            sqlFailure("23514",pair[1],()->jdbc.update("update iam_user set "+pair[0]+" where id=?",u.userId()));
        sqlFailure("23502",null,()->jdbc.update("update iam_user set auth_revocation_generation=null where id=?",u.userId()));
        sqlFailure("23503","fk_iam_user_company_employee",()->jdbc.update("update iam_user set company_id=? where id=?",UUID.randomUUID(),u.userId()));
        var pk=catchThrowable(()->jdbc.update("update iam_user set id=? where id=?",u.userId(),v.userId()));assertThat(pg(pk).getSQLState()).isEqualTo("23505");assertThat(pg(pk).getServerErrorMessage().getConstraint()).isEqualTo("pk_iam_user");assertThat(store.translate((RuntimeException)pk)).isSameAs(pk);
        for(String field:List.of("employee_id","login_key")){
            Object value=field.equals("employee_id")?a.employee().id():"alpha";var error=catchThrowable(()->jdbc.update("update iam_user set "+field+"=? where id=?",value,v.userId()));
            assertThat(store.translate((RuntimeException)error)).isInstanceOfSatisfying(IamFailure.class,f->assertThat(f.code()).isEqualTo(field.equals("employee_id")?EMPLOYEE_ACCOUNT_CONFLICT:LOGIN_KEY_CONFLICT));
        }
        jdbc.execute("create unique index uq_iam01_test_other on iam_user(last_status_reason)");
        try{jdbc.update("update iam_user set last_status_reason='test-only collision',status_changed_at=now() where id=?",u.userId());sqlFailure("23505","uq_iam01_test_other",()->jdbc.update("update iam_user set last_status_reason='test-only collision',status_changed_at=now() where id=?",v.userId()));}
        finally{jdbc.execute("drop index uq_iam01_test_other");}
        var both=catchThrowable(()->service.createUser(create(a,"alpha")));assertThat(both).isInstanceOfSatisfying(IamFailure.class,f->assertThat(f.code()).isIn(EMPLOYEE_ACCOUNT_CONFLICT,LOGIN_KEY_CONFLICT));
        var fk=jdbc.queryForMap("select confupdtype,confdeltype,condeferrable from pg_constraint where conname='fk_iam_user_company_employee'");assertThat(fk.get("confupdtype").toString()).isEqualTo("r");assertThat(fk.get("confdeltype").toString()).isEqualTo("r");assertThat(fk.get("condeferrable")).isEqualTo(false);
        for(String field:List.of("id","company_id","employee_id","login_key","account_status","version","created_at","updated_at"))sqlFailure("23502",null,()->jdbc.update("update iam_user set "+field+"=null where id=?",u.userId()));
        System.out.println("I1 T24 isolated PK/FK/CHECK/NOTNULL, exact duplicate mappings and RESTRICT/nondeferrable validated");
    }
    @ParameterizedTest @ValueSource(strings={"create","enable","disable","terminated","leave","terminatedDisable","leaveDisable"})
    void t25_flushFailureRollsBackEntireIamRoot(String mode){
        var e=employee("A");var beforeEmployee=row(e.employee().id());var h=history(e.employee().id());
        UserView u=mode.equals("create")?null:service.createUser(create(e,"account"));if(mode.equals("disable")||mode.endsWith("Disable"))u=service.enableUser(change(u,0));
        if(mode.startsWith("terminated")){terminations.terminateEmployee(end(e));beforeEmployee=row(e.employee().id());h=history(e.employee().id());}
        if(mode.startsWith("leave")){jdbc.update("update hr_employee set employment_status='ON_LEAVE' where id=?",e.employee().id());beforeEmployee=row(e.employee().id());}
        var before=u==null?null:user(u.userId());final var current=u;
        doAnswer(c->{c.callRealMethod();throw new IllegalStateException("injected post-flush abort");}).when(store).flush();
        assertThatThrownBy(()->{if(mode.equals("create"))service.createUser(create(e,"account"));else if(mode.equals("disable")||mode.endsWith("Disable"))service.disableUser(change(current,current.version()));else service.enableUser(change(current,current.version()));}).isInstanceOf(RuntimeException.class);
        if(current==null)assertThat(count()).isZero();else assertThat(user(current.userId())).isEqualTo(before);unchanged(e,beforeEmployee,h);
        System.out.println("I1 T25 whole IAM root rollback after actual flush; mode="+mode+"; independent DB v/g/reason/time/HR baseline preserved");
    }
    @ParameterizedTest @ValueSource(strings={"create","enable","disable"})
    void t25_t27_realDeferredCommitAbort(String mode){
        boolean creating=mode.equals("create");var e=employee("A");var created=creating?null:service.createUser(create(e,"account"));
        var u=mode.equals("disable")?service.enableUser(change(created,0)):created;var before=u==null?null:user(u.userId());
        jdbc.execute("create function iam01_abort_commit() returns trigger language plpgsql as $$ begin raise exception 'test commit abort' using errcode='23514'; return null; end $$");
        jdbc.execute("create constraint trigger iam01_abort after insert or update on iam_user deferrable initially deferred for each row execute function iam01_abort_commit()");
        try{var f=catchThrowable(()->{if(creating)service.createUser(create(e,"account"));else if(mode.equals("disable"))service.disableUser(change(u,u.version()));else service.enableUser(change(u,u.version()));});assertThat(pg(f).getSQLState()).isEqualTo("23514");assertThat(f).isNotInstanceOf(IamFailure.class);if(creating)assertThat(count()).isZero();else assertThat(user(u.userId())).isEqualTo(before);}
        finally{jdbc.execute("drop trigger iam01_abort on iam_user");jdbc.execute("drop function iam01_abort_commit()");}
        System.out.println("I1 T25/T27 real commit-time abort23514; no success DTO and all IAM writes rolled back");
    }
    UserService withManager(PlatformTransactionManager m){return new UserService(store,guard,organization,m,clock,30);}
    @Test void t26_actualCommitThenAckWrapperAndRetries(){
        var e=employee("A");var commits=new AtomicInteger();var rolls=new AtomicInteger();var wrapped=withManager(new PlatformTransactionManager(){
            public TransactionStatus getTransaction(TransactionDefinition d){return manager.getTransaction(d);}
            public void commit(TransactionStatus s){manager.commit(s);commits.incrementAndGet();throw new TransactionSystemException("test lost ACK after actual commit");}
            public void rollback(TransactionStatus s){rolls.incrementAndGet();manager.rollback(s);}
        });
        assertThatThrownBy(()->wrapped.createUser(create(e,"account"))).isInstanceOf(TransactionSystemException.class);assertThat(count()).isEqualTo(1);var u=queries.findUserByEmployee(company,e.employee().id()).orElseThrow();
        assertThatThrownBy(()->service.createUser(create(e,"account"))).isInstanceOf(IamFailure.class);assertThatThrownBy(()->wrapped.enableUser(change(u,0))).isInstanceOf(TransactionSystemException.class);
        failure(VERSION_CONFLICT,()->service.enableUser(change(u,0)));assertThat(service.enableUser(change(u,1)).version()).isEqualTo(1);assertThat(service.enableUser(change(u,1)).version()).isEqualTo(1);
        terminations.terminateEmployee(end(e));hrFailure(HrFailure.Code.EMPLOYEE_TERMINATED,()->service.createUser(create(e,"account")));
        assertThat(commits.get()).isEqualTo(2);assertThat(rolls.get()).isZero();assertThat(count()).isEqualTo(1);
        System.out.println("I1 T26 actual commit+test ACK wrapper; no automatic retry/compensation; create UNIQUE or changed HR precondition; not real network/receipt proof");
    }
    @Test void am01_overflowAndNegativeGeneration(){
        var e=employee("A");var u=service.createUser(create(e,"account"));service.enableUser(change(u,0));jdbc.update("update iam_user set auth_revocation_generation=? where id=?",Long.MAX_VALUE,u.userId());var before=user(u.userId());var hr=row(e.employee().id());
        assertThatThrownBy(()->service.disableUser(change(u,1))).isInstanceOf(ArithmeticException.class);assertThat(user(u.userId())).isEqualTo(before);assertThat(row(e.employee().id())).isEqualTo(hr);
        sqlFailure("23514","ck_iam_user_auth_revocation_generation",()->jdbc.update("update iam_user set auth_revocation_generation=-1 where id=?",u.userId()));
        sqlFailure("22003",null,()->jdbc.update("update iam_user set auth_revocation_generation=auth_revocation_generation+1 where id=?",u.userId()));
        assertThat(user(u.userId())).isEqualTo(before);System.out.println("AM01 Java exact overflow entire root failure, DB negative23514 and overflow22003, no HR generation write");
    }
    @Test void t28_internalQueriesPaginationAndScalarFreshness(){
        var e=employee("A");var a=service.createUser(create(e,"zzz"));var b=service.createUser(create(employee("B"),"aaa"));var c=service.createUser(create(employee("C"),"bbb"));
        assertThat(queries.findUser(company,UUID.randomUUID())).isEmpty();assertThat(queries.findUser(company,site)).isEmpty();assertThat(queries.findUserByEmployee(company,UUID.randomUUID())).isEmpty();
        assertThat(queries.listUsers(company,null,0,null).items()).extracting(UserListItem::loginKey).containsExactly("aaa","bbb","zzz");
        assertThat(queries.listUsers(company,null,0,1).hasNext()).isTrue();assertThat(queries.listUsers(company,null,2,1).hasNext()).isFalse();assertThat(queries.listUsers(company,null,3,1).items()).isEmpty();assertThat(queries.listUsers(company,null,0,100).items()).hasSize(3);
        for(int size:new int[]{0,101})failure(INVALID_INPUT,()->queries.listUsers(company,null,0,size));failure(INVALID_INPUT,()->queries.listUsers(company,null,-1,20));failure(INVALID_INPUT,()->queries.listUsers(company,null,Integer.MAX_VALUE,100));failure(INVALID_INPUT,()->queries.listUsers(company,"EMPLOYED",0,20));
        assertThatThrownBy(()->queries.findUser(UUID.randomUUID(),a.userId())).isInstanceOf(OrganizationFailure.class);
        tx(()->{var stale=em.find(User.class,a.userId());assertThat(stale.facts().version()).isZero();assertThat(result(job("writer",()->service.enableUser(change(a,0))))).isInstanceOf(UserView.class);
            var actual=queries.findUser(company,a.userId()).orElseThrow();assertThat(actual.version()).isEqualTo(1);assertThat(actual.accountStatus()).isEqualTo("ENABLED");assertThat(stale.facts().version()).isZero();assertThat(em.contains(stale)).isTrue();return null;});
        assertThat(queries.listUsers(company,"ENABLED",0,20).items()).extracting(UserListItem::userId).containsExactly(a.userId());terminations.terminateEmployee(end(e));assertThat(queries.findUserByEmployee(company,e.employee().id())).isPresent();
        doAnswer(inv->{jdbc.execute("select 1/0");return Optional.empty();}).when(store).findUser(any(),any());assertThatThrownBy(()->queries.findUser(company,a.userId())).isInstanceOf(RuntimeException.class);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void t27_actualTimeoutGuardOrUserLock(boolean creating){
        var e=employee("A");var u=creating?null:service.createUser(create(e,"account"));var held=gate();var release=gate();var id=new AtomicReference<Map<String,Object>>();
        var locker=job("locker",()->tx(()->{jdbc.queryForObject("select id from "+(creating?"hr_employee":"iam_user")+" where id=? FOR UPDATE",UUID.class,creating?e.employee().id():u.userId());id.set(physical());held.countDown();await(release);return true;}));await(held);
        doAnswer(c->{c.callRealMethod();assertThat(jdbc.queryForObject("show lock_timeout",String.class)).isEqualTo("5s");assertThat(jdbc.queryForObject("show statement_timeout",String.class)).isEqualTo("30s");jdbc.execute("set local lock_timeout='800ms'");return null;}).when(store).boundRootWaits(anyInt());
        var worker=job("worker",()->creating?service.createUser(create(e,"account")):service.enableUser(change(u,0)));blocked(creating?"FOR SHARE":"iam_user",id.get());var f=result(worker);assertThat(f).isInstanceOf(RuntimeException.class).isNotInstanceOf(IamFailure.class).isNotInstanceOf(HrFailure.class);assertThat(pg((Throwable)f).getSQLState()).isEqualTo("55P03");release.countDown();assertThat(result(locker)).isEqualTo(true);
        if(creating)assertThat(count()).isZero();else assertThat(user(u.userId()).get("version")).isEqualTo(0L);System.out.println("I1 T27 actual timeout55P03 no business-error translation; finite lock5s/statement30s defaults");
    }
    @Test void t27_actualDeadlockRemainsTechnical(){
        var a=service.createUser(create(employee("A"),"aaa"));var b=service.createUser(create(employee("B"),"bbb"));var held=gate();var reverseHeld=gate();var release=gate();
        doAnswer(c->{c.callRealMethod();jdbc.execute("set local deadlock_timeout='100ms'");held.countDown();await(release);jdbc.queryForObject("select id from iam_user where id=? FOR UPDATE",UUID.class,b.userId());return null;}).when(store).flush();
        var actual=job("actual",()->service.enableUser(change(a,0)));await(held);var reverse=job("reverse",()->tx(()->{jdbc.execute("set local deadlock_timeout='100ms'");jdbc.queryForObject("select id from iam_user where id=? FOR UPDATE",UUID.class,b.userId());reverseHeld.countDown();jdbc.queryForObject("select id from iam_user where id=? FOR UPDATE",UUID.class,a.userId());return true;}));
        await(reverseHeld);blocked("iam_user",null);release.countDown();var r1=result(actual);var r2=result(reverse);var failures=List.of(r1,r2).stream().filter(RuntimeException.class::isInstance).map(RuntimeException.class::cast).toList();assertThat(failures).hasSize(1);assertThat(pg(failures.getFirst()).getSQLState()).isEqualTo("40P01");assertThat(store.translate(failures.getFirst())).isSameAs(failures.getFirst());
        assertThat(user(a.userId()).get("version")).isEqualTo(r1 instanceof RuntimeException?0L:1L);System.out.println("I1 T27 actual deadlock40P01 via test-only reverse locker; no automatic retry");
    }
    @Test void t27_connectionFailureAfterInsertIsNotEmptyOrConflict(){
        var e=employee("A");doAnswer(c->{c.callRealMethod();int pid=(int)physical().get("pid");assertThat(result(job("killer",()->jdbc.queryForObject("select pg_terminate_backend(?)",Boolean.class,pid)))).isEqualTo(true);return null;}).when(store).flush();
        assertThatThrownBy(()->service.createUser(create(e,"account"))).isInstanceOf(RuntimeException.class).isNotInstanceOf(IamFailure.class).isNotInstanceOf(HrFailure.class);assertThat(count()).isZero();System.out.println("I1 T27 actual connection terminated after insert flush; confirmed abort/User0, no fabricated duplicate");
    }
    @Test void t14_rootDateCapturedOnceAcrossMidnightLockWait(){
        var e=employee("A");var held=gate();var release=gate();var clockCalls=new AtomicInteger();var original=Instant.parse("2026-10-11T14:59:00Z");
        when(clock.instant()).thenAnswer(c->{clockCalls.incrementAndGet();return original;});var holder=job("holder",()->tx(()->{jdbc.queryForObject("select id from hr_employee where id=? FOR UPDATE",UUID.class,e.employee().id());held.countDown();await(release);return true;}));await(held);
        var pending=job("create",()->service.createUser(create(e,"account")));blocked("FOR SHARE",null);doReturn(Instant.parse("2026-10-11T15:01:00Z")).when(clock).instant();release.countDown();assertThat(result(holder)).isEqualTo(true);var r=result(pending);assertThat(r).isInstanceOfSatisfying(UserView.class,u->assertThat(u.createdAt()).isEqualTo(original));assertThat(clockCalls.get()).isEqualTo(1);verify(guard).requireLinkable(company,e.employee().id(),today);
    }
}
