package com.kflow.erp.iam;

import com.kflow.erp.KFlowErpApplication;
import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.application.*;
import com.kflow.erp.hr.domain.EmployeeLinkFacts;
import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.application.*;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
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

/** Actual HR/IAM roots. Test hooks pause completed SQL, never replace successful business validation. */
@Testcontainers @SpringBootTest(classes=KFlowErpApplication.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS) @Timeout(90)
class UserRoleHrIT {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16")
        .withCommand("postgres","-c","log_statement=all","-c","log_line_prefix=%p %x ");
    @Autowired UserRoleService grants;
    @Autowired UserRoleQueryService queries;
    @Autowired RoleService roles;
    @Autowired UserService users;
    @Autowired EmployeeAssignmentService transfers;
    @Autowired EmployeeTerminationService terminations;
    @Autowired OrganizationService organization;
    @Autowired OrganizationAssignmentGuard orgGuard;
    @MockitoSpyBean UserRoleStore grantStore;
    @MockitoSpyBean RoleStore roleStore;
    @MockitoSpyBean IamStore userStore;
    @MockitoSpyBean HrStore hrStore;
    @MockitoSpyBean HrAssignmentStore hrLocks;
    @MockitoSpyBean HrAccountLinkStore links;
    @MockitoSpyBean EmployeeRoleGrantGuard guard;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean(name="iamClock") Clock clock;
    @MockitoBean(name="hrClock") Clock hrClock;
    final Instant now=Instant.parse("2026-10-11T03:00:00Z");
    final LocalDate today=LocalDate.of(2026,10,11),joined=LocalDate.of(2026,10,1);
    UUID company,site,department,position,position2;
    ExecutorService pool;List<CountDownLatch> gates;
    @BeforeEach void setup(){
        when(clock.instant()).thenReturn(now);when(hrClock.instant()).thenReturn(now);
        for(String table:List.of("iam_user_role","iam_role","iam_user","hr_employee_assignment","hr_employee","organization_site","organization_department","organization_position"))jdbc.update("delete from "+table);
        company=organization.getCompany().companyId();site=organization.registerSite("S","site").siteId();department=organization.registerDepartment("D","department").departmentId();position=organization.registerPosition("P","position").positionId();position2=organization.registerPosition("P2","other").positionId();
        pool=Executors.newFixedThreadPool(5);gates=new ArrayList<>();
    }
    @AfterEach void cleanup()throws Exception{gates.forEach(CountDownLatch::countDown);pool.shutdownNow();assertThat(pool.awaitTermination(20,TimeUnit.SECONDS)).isTrue();}
    UserView user(String key){return user(key,joined);}
    UserView user(String key,LocalDate start){
        var registration=new EmployeeRegistrationService(hrStore,orgGuard,manager,Clock.fixed(start.atTime(12,0).atZone(ZoneId.of("Asia/Seoul")).toInstant(),ZoneOffset.UTC));
        var e=registration.createEmployee(new CreateEmployee(company,key.replace('.','-'),"synthetic employee",start,site,department,position,"registered"));
        return users.createUser(new CreateUser(company,e.employee().id(),key,"account"));
    }
    RoleView role(String code){var r=roles.createRole(new CreateRole(company,code,code,"role"));return roles.activateRole(new ChangeRoleStatus(company,r.roleId(),0L,"activate"));}
    GrantRole input(UserView u,RoleView r,UUID id){return new GrantRole(company,id,u.userId(),r.roleId(),"grant");}
    UserRoleView grant(UserView u,RoleView r){return grants.grantRole(input(u,r,UUID.randomUUID()));}
    ChangeRoleStatus deactivate(RoleView r){return new ChangeRoleStatus(company,r.roleId(),r.version(),"all revoked");}
    TerminateEmployee end(UserView u){return new TerminateEmployee(company,u.employeeId(),((Number)row("hr_employee",u.employeeId()).get("version")).longValue(),today,"termination");}
    ChangeAssignment transfer(UserView u){return new ChangeAssignment(company,u.employeeId(),((Number)row("hr_employee",u.employeeId()).get("version")).longValue(),today,site,department,position2,"transfer");}
    Map<String,Object> row(String table,UUID id){return jdbc.queryForMap("select * from "+table+" where id=?",id);}
    List<Map<String,Object>> table(String table){return jdbc.queryForList("select * from "+table+" order by id");}
    List<Map<String,Object>> history(UserView u){return jdbc.queryForList("select * from hr_employee_assignment where employee_id=? order by effective_from",u.employeeId());}
    Map<String,List<Map<String,Object>>> originals(){var m=new LinkedHashMap<String,List<Map<String,Object>>>();for(String t:List.of("organization_company","organization_site","organization_department","organization_position","iam_user"))m.put(t,table(t));return m;}
    void unchanged(Map<String,List<Map<String,Object>>> before){before.forEach((t,v)->assertThat(table(t)).as(t).isEqualTo(v));}
    Map<String,Object> physical(){return em.unwrap(Session.class).doReturningWork(c->{try(var s=c.createStatement();var r=s.executeQuery("select pg_backend_pid(),pg_current_xact_id()::text")){r.next();return Map.of("pid",r.getInt(1),"xid",r.getString(2));}});}
    String sql(Map<String,Object> id){return postgres.getLogs().lines().filter(l->l.contains(id.get("pid")+" "+id.get("xid")+" ")).reduce("",(a,b)->a+b+"\n");}
    CountDownLatch gate(){var x=new CountDownLatch(1);gates.add(x);return x;}
    void await(CountDownLatch x){try{assertThat(x.await(15,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    boolean thread(String s){return Thread.currentThread().getName().equals(s);}
    Future<Object> job(String name,Supplier<?> action){return pool.submit(()->{String old=Thread.currentThread().getName();Thread.currentThread().setName(name);try{return action.get();}catch(RuntimeException e){System.out.println("I3 worker "+name+" returned "+e);e.printStackTrace(System.out);return e;}finally{Thread.currentThread().setName(old);}});}
    Object result(Future<Object> f){try{return f.get(20,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}}
    <T>T tx(Supplier<T> action){var t=new TransactionTemplate(manager);t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return t.execute(s->action.get());}
    void blocked(String query,Map<String,Object> blocker){long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<until){for(var r:jdbc.queryForList("select pid,pg_blocking_pids(pid)::text blockers,query from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query ilike ?","%"+query+"%")){if(Arrays.asList(r.get("blockers").toString().replace("{","").replace("}","").split(",")).contains(blocker.get("pid").toString())){System.out.println("I3 actual wait "+r+" blocker="+blocker);return;}}try{Thread.sleep(10);}catch(InterruptedException e){throw new AssertionError(e);}}fail("Expected actual blocker for "+query+" -> "+blocker);}
    PSQLException pg(Throwable e){for(Throwable c=e;c!=null;c=c.getCause())if(c instanceof PSQLException p)return p;throw new AssertionError("No PostgreSQL cause",e);}
    void hrFailure(HrFailure.Code code,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(HrFailure.class,e->assertThat(e.code()).isEqualTo(code));}
    void trace(String label,Map<String,Map<String,Object>> ids){System.out.println("I3 "+label+" physical roots="+ids);for(var id:ids.values())System.out.println(sql(id));}

    @Test void t30_guardEligibilityAndDistinctPurpose(){
        var u=user("eligibility");var r=role("R");var before=originals();
        assertThat(grant(u,r).state()).isEqualTo("UNREVOKED");
        hrFailure(HrFailure.Code.EMPLOYEE_NOT_FOUND,()->tx(()->{guard.requireGrantableEmployee(company,UUID.randomUUID(),today);return null;}));
        hrFailure(HrFailure.Code.EMPLOYEE_COMPANY_MISMATCH,()->tx(()->{guard.requireGrantableEmployee(UUID.randomUUID(),u.employeeId(),today);return null;}));
        hrFailure(HrFailure.Code.EMPLOYMENT_DATE_UNSUPPORTED,()->tx(()->{guard.requireGrantableEmployee(company,u.employeeId(),joined.minusDays(1));return null;}));
        var leave=user("leave");jdbc.update("update hr_employee set employment_status='ON_LEAVE' where id=?",leave.employeeId());
        hrFailure(HrFailure.Code.ON_LEAVE_ROLE_GRANT_UNSUPPORTED,()->grant(leave,r));
        var term=user("terminated");terminations.terminateEmployee(end(term));hrFailure(HrFailure.Code.EMPLOYEE_TERMINATED,()->grant(term,r));
        assertThatThrownBy(()->grants.grantRole(new GrantRole(company,UUID.randomUUID(),UUID.randomUUID(),r.roleId(),"x"))).isInstanceOfSatisfying(IamFailure.class,e->assertThat(e.code()).isEqualTo(IamFailure.Code.USER_NOT_FOUND));
        assertThat(row("iam_user",u.userId())).isEqualTo(before.get("iam_user").getFirst());
        assertThat(jdbc.queryForObject("select count(*) from iam_user_role",Integer.class)).isEqualTo(1);
        System.out.println("I3 T30: EMPLOYED grant, TERMINATED/ON_LEAVE/date/company/missing distinguished; isolated ON_LEAVE fixture only, no production leave API");
    }

    @Test void t29_guardFailureCaughtStillRollsBackRootAndRetainsSameConnection(){
        var u=user("boundary");var identity=new AtomicReference<Map<String,Object>>();
        doAnswer(c->{var facts=c.callRealMethod();identity.set(physical());return facts;}).when(links).lockForLink(any());
        tx(()->{var root=physical();guard.requireGrantableEmployee(company,u.employeeId(),today);assertThat(identity.get()).isEqualTo(root);assertThat(jdbc.queryForObject("show transaction_isolation",String.class)).isEqualTo("read committed");return null;});
        hrFailure(HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION,()->guard.requireGrantableEmployee(company,u.employeeId(),today));
        var before=row("iam_user",u.userId());
        assertThatThrownBy(()->tx(()->{jdbc.update("update iam_user set creation_reason='temporary' where id=?",u.userId());try{guard.requireGrantableEmployee(company,UUID.randomUUID(),today);}catch(HrFailure expected){}return null;})).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(row("iam_user",u.userId())).isEqualTo(before);
        System.out.println("I3 T29 HR purpose Guard same PID/XID="+identity.get()+" caught failure marks entire root rollback-only");
    }

    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6})
    void t31_threeIndependentRootsSixRequiredSchedules(int schedule){
        var u=user("triple.target");var other=user("triple.other");var r=role("R");var q=role("Q");
        var s0=grant(other,r);var h0=grant(u,r);grants.revokeUserRole(new RevokeUserRole(company,h0.grantId(),0L,"previous manual"));var q0=grant(u,q);
        var hBefore=row("iam_user_role",h0.grantId());var qBefore=row("iam_user_role",q0.grantId());var qRoleBefore=row("iam_role",q.roleId());var sBefore=row("iam_user_role",s0.grantId());var roleBefore=row("iam_role",r.roleId());var employeeBefore=row("hr_employee",u.employeeId());var timelineBefore=history(u);var otherBefore=row("hr_employee",other.employeeId());var otherTimeline=history(other);var immutable=originals();
        UUID id=UUID.randomUUID();var hold=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();
        doAnswer(c->{c.callRealMethod();ids.put(Thread.currentThread().getName(),physical());return null;}).when(grantStore).requireWritableTransaction();
        doAnswer(c->{ids.put("D",physical());var value=c.callRealMethod();if(thread("D")&&(schedule==3||schedule==4)){hold.countDown();await(release);if(schedule==4)throw new IllegalStateException("D confirmed rollback");}return value;}).when(roleStore).lockRole(any(),any());
        doAnswer(c->{ids.put("H",physical());return c.callRealMethod();}).when(hrLocks).lockEmployee(any(),any());
        doAnswer(c->{c.callRealMethod();if(thread("G")&&schedule!=3&&schedule!=4){hold.countDown();await(release);if(schedule==2)throw new IllegalStateException("G confirmed rollback");}return null;}).when(grantStore).flush();
        doAnswer(c->{c.callRealMethod();if(thread("D")&&schedule==5){assertThat(jdbc.queryForObject("select count(*) from iam_user_role where role_id=? and revoked_at is null",Integer.class,r.roleId())).isZero();assertThat(row("iam_role",r.roleId()).get("status")).isEqualTo("INACTIVE");throw new IllegalStateException("D all grants and Role flushed then abort");}return null;}).when(roleStore).flush();
        doAnswer(c->{c.callRealMethod();if(thread("H")&&schedule==6&&"TERMINATED".equals(row("hr_employee",u.employeeId()).get("employment_status"))){assertThat(history(u).getLast().get("effective_to")).isEqualTo(java.sql.Date.valueOf(today));throw new IllegalStateException("H Employee and tail flushed then abort");}return null;}).when(hrStore).flush();
        Future<Object> g,d,h;
        if(schedule==3||schedule==4){
            d=job("D",()->roles.deactivateRole(deactivate(r)));await(hold);g=job("G",()->grants.grantRole(input(u,r,id)));blocked("iam_role",ids.get("D"));h=job("H",()->terminations.terminateEmployee(end(u)));blocked("hr_employee",ids.get("G"));
        }else{
            g=job("G",()->grants.grantRole(input(u,r,id)));await(hold);d=job("D",()->roles.deactivateRole(deactivate(r)));h=job("H",()->terminations.terminateEmployee(end(u)));blocked("iam_role",ids.get("G"));blocked("hr_employee",ids.get("G"));
        }
        assertThat(Set.of(ids.get("G").get("pid"),ids.get("D").get("pid"),ids.get("H").get("pid"))).hasSize(3);
        assertThat(Set.of(ids.get("G").get("xid"),ids.get("D").get("xid"),ids.get("H").get("xid"))).hasSize(3);
        release.countDown();var gr=result(g);var dr=result(d);var hr=result(h);
        boolean grantOk=schedule!=2&&schedule!=3,deactivateOk=schedule!=4&&schedule!=5,hrOk=schedule!=6;
        assertThat(gr).isInstanceOf(grantOk?UserRoleView.class:RuntimeException.class);if(schedule==3)assertThat(((IamFailure)gr).code()).isEqualTo(IamFailure.Code.ROLE_INACTIVE);
        assertThat(dr).isInstanceOf(deactivateOk?RoleView.class:RuntimeException.class);assertThat(hr).isInstanceOf(hrOk?EmployeeTerminated.class:RuntimeException.class);
        var expected=new HashSet<UUID>();expected.add(s0.grantId());if(grantOk)expected.add(id);
        var newRows=jdbc.queryForList("select * from iam_user_role where role_id=? and id<>? order by id",r.roleId(),h0.grantId());assertThat(newRows).extracting(x->(UUID)x.get("id")).containsExactlyInAnyOrderElementsOf(expected);
        for(var row:newRows){assertThat(row.get("version")).isEqualTo(deactivateOk?1L:0L);assertThat(row.get("revoke_kind")).isEqualTo(deactivateOk?"ROLE_DEACTIVATION":null);assertThat(row.get("revoke_reason")).isEqualTo(deactivateOk?"all revoked":null);assertThat(row.get("revoked_at")).isEqualTo(deactivateOk?java.sql.Timestamp.from(now):null);assertThat(row.get("grant_reason")).isEqualTo("grant");}
        if(deactivateOk){assertThat(row("iam_role",r.roleId()).get("status")).isEqualTo("INACTIVE");assertThat(row("iam_role",r.roleId()).get("version")).isEqualTo(r.version()+1);assertThat(row("iam_role",r.roleId()).get("last_change_reason")).isEqualTo("all revoked");}else{assertThat(row("iam_role",r.roleId())).isEqualTo(roleBefore);assertThat(row("iam_user_role",s0.grantId())).isEqualTo(sBefore);}
        if(hrOk){var finalEmployee=row("hr_employee",u.employeeId());assertThat(finalEmployee.get("employment_status")).isEqualTo("TERMINATED");assertThat(finalEmployee.get("version")).isEqualTo(((Number)employeeBefore.get("version")).longValue()+1);assertThat(finalEmployee.get("auth_revocation_generation")).isEqualTo(((Number)employeeBefore.get("auth_revocation_generation")).longValue()+1);assertThat(history(u).getLast().get("effective_to")).isEqualTo(java.sql.Date.valueOf(today));assertThat(history(u).getLast().get("version")).isEqualTo(1L);}else{assertThat(row("hr_employee",u.employeeId())).isEqualTo(employeeBefore);assertThat(history(u)).isEqualTo(timelineBefore);}
        assertThat(row("iam_user_role",h0.grantId())).isEqualTo(hBefore);assertThat(row("iam_user_role",q0.grantId())).isEqualTo(qBefore);assertThat(row("iam_role",q.roleId())).isEqualTo(qRoleBefore);assertThat(row("hr_employee",other.employeeId())).isEqualTo(otherBefore);unchanged(immutable);
        // Compare complete rows: only the explicitly allowed transition columns may differ.
        var expectedRole=new HashMap<>(roleBefore);if(deactivateOk){expectedRole.put("status","INACTIVE");expectedRole.put("version",r.version()+1);expectedRole.put("last_change_reason","all revoked");expectedRole.put("last_change_kind","DEACTIVATE");expectedRole.put("updated_at",java.sql.Timestamp.from(now));}assertThat(row("iam_role",r.roleId())).isEqualTo(expectedRole);
        var expectedEmployee=new HashMap<>(employeeBefore);var expectedTail=new HashMap<>(timelineBefore.getLast());
        if(hrOk){expectedEmployee.put("employment_status","TERMINATED");expectedEmployee.put("termination_effective_on",java.sql.Date.valueOf(today));expectedEmployee.put("termination_reason","termination");expectedEmployee.put("version",((Number)employeeBefore.get("version")).longValue()+1);expectedEmployee.put("auth_revocation_generation",((Number)employeeBefore.get("auth_revocation_generation")).longValue()+1);expectedEmployee.put("updated_at",java.sql.Timestamp.from(now));expectedTail.put("effective_to",java.sql.Date.valueOf(today));expectedTail.put("version",((Number)expectedTail.get("version")).longValue()+1);expectedTail.put("updated_at",java.sql.Timestamp.from(now));}
        assertThat(row("hr_employee",u.employeeId())).isEqualTo(expectedEmployee);assertThat(history(u)).containsExactly(expectedTail);assertThat(history(other)).isEqualTo(otherTimeline);
        var expectedS0=new HashMap<>(sBefore);if(deactivateOk){expectedS0.put("version",1L);expectedS0.put("revoked_at",java.sql.Timestamp.from(now));expectedS0.put("revoke_reason","all revoked");expectedS0.put("revoke_kind","ROLE_DEACTIVATION");}assertThat(row("iam_user_role",s0.grantId())).isEqualTo(expectedS0);
        if(grantOk){var actual=row("iam_user_role",id);assertThat(actual.get("company_id")).isEqualTo(company);assertThat(actual.get("user_id")).isEqualTo(u.userId());assertThat(actual.get("role_id")).isEqualTo(r.roleId());assertThat(actual.get("granted_at")).isEqualTo(java.sql.Timestamp.from(now));}
        trace("T31 required triple schedule="+schedule+" S="+expected+" Role="+row("iam_role",r.roleId())+" grants="+newRows+" Employee="+row("hr_employee",u.employeeId())+" timeline="+history(u),ids);
    }

    @ParameterizedTest @CsvSource({"true,true","true,false","false,true","false,false"})
    void t31_grantAndRealTerminationBothDirections(boolean grantFirst,boolean commit){
        var u=user("hr.race");var r=role("R");var before=row("hr_employee",u.employeeId());var timeline=history(u);var immutable=originals();UUID id=UUID.randomUUID();var held=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();
        doAnswer(c->{c.callRealMethod();ids.put("G",physical());return null;}).when(grantStore).requireWritableTransaction();
        doAnswer(c->{ids.put("H",physical());return c.callRealMethod();}).when(hrLocks).lockEmployee(any(),any());
        doAnswer(c->{c.callRealMethod();if(thread("G")&&grantFirst){held.countDown();await(release);if(!commit)throw new IllegalStateException("G rollback");}return null;}).when(grantStore).flush();
        doAnswer(c->{c.callRealMethod();if(thread("H")&&!grantFirst&&"TERMINATED".equals(row("hr_employee",u.employeeId()).get("employment_status"))){held.countDown();await(release);if(!commit)throw new IllegalStateException("H rollback");}return null;}).when(hrStore).flush();
        Future<Object> g,h;if(grantFirst){g=job("G",()->grants.grantRole(input(u,r,id)));await(held);h=job("H",()->terminations.terminateEmployee(end(u)));blocked("hr_employee",ids.get("G"));}else{h=job("H",()->terminations.terminateEmployee(end(u)));await(held);g=job("G",()->grants.grantRole(input(u,r,id)));blocked("hr_employee",ids.get("H"));}release.countDown();var gr=result(g);var hr=result(h);
        boolean gOk=grantFirst?commit:!commit,hOk=grantFirst||commit;
        assertThat(gr).isInstanceOf(gOk?UserRoleView.class:RuntimeException.class);assertThat(hr).isInstanceOf(hOk?EmployeeTerminated.class:RuntimeException.class);
        if(!grantFirst&&commit)assertThat(((HrFailure)gr).code()).isEqualTo(HrFailure.Code.EMPLOYEE_TERMINATED);
        assertThat(queries.findUserRole(company,id).isPresent()).isEqualTo(gOk);if(gOk)assertThat(queries.findUserRole(company,id).orElseThrow().state()).isEqualTo("UNREVOKED");
        if(hOk){assertThat(row("hr_employee",u.employeeId()).get("employment_status")).isEqualTo("TERMINATED");assertThat(history(u).getLast().get("effective_to")).isEqualTo(java.sql.Date.valueOf(today));}else{assertThat(row("hr_employee",u.employeeId())).isEqualTo(before);assertThat(history(u)).isEqualTo(timeline);}
        unchanged(immutable);assertThat(row("iam_role",r.roleId()).get("version")).isEqualTo(r.version());trace("T31 two-root grantFirst="+grantFirst+" commit="+commit,ids);
    }

    @ParameterizedTest @CsvSource({"TRANSFER,true,true","TRANSFER,true,false","TRANSFER,false,true","TRANSFER,false,false","ENABLE,true,true","ENABLE,true,false","ENABLE,false,true","ENABLE,false,false","DISABLE,true,true","DISABLE,true,false","DISABLE,false,true","DISABLE,false,false"})
    void t32_realTransferAndUserStateRaces(String operation,boolean grantFirst,boolean commit){
        var initial=user("state.race");var u=operation.equals("DISABLE")?users.enableUser(new ChangeUserStatus(company,initial.userId(),0L,"enabled")):initial;var r=role("R");
        var erow=row("hr_employee",u.employeeId());var hrow=history(u);var urow=row("iam_user",u.userId());var rrow=row("iam_role",r.roleId());UUID id=UUID.randomUUID();var held=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();
        doAnswer(c->{c.callRealMethod();ids.put("G",physical());return null;}).when(grantStore).requireWritableTransaction();
        doAnswer(c->{ids.put("W",physical());var value=c.callRealMethod();if(thread("W")&&!grantFirst){held.countDown();await(release);if(!commit)throw new IllegalStateException("transfer rollback");}return value;}).when(hrLocks).lockEmployee(any(),any());
        doAnswer(c->{ids.put("W",physical());var value=c.callRealMethod();if(thread("W")&&!grantFirst){held.countDown();await(release);if(!commit)throw new IllegalStateException("state rollback");}return value;}).when(userStore).lockUser(any(),any());
        doAnswer(c->{c.callRealMethod();if(thread("G")&&grantFirst){held.countDown();await(release);if(!commit)throw new IllegalStateException("grant rollback");}return null;}).when(grantStore).flush();
        Supplier<Object> writer=()->switch(operation){case "TRANSFER"->transfers.changeAssignment(transfer(u));case "ENABLE"->users.enableUser(new ChangeUserStatus(company,u.userId(),u.version(),"enable"));default->users.disableUser(new ChangeUserStatus(company,u.userId(),u.version(),"disable"));};
        Future<Object> g,w;if(grantFirst){g=job("G",()->grants.grantRole(input(u,r,id)));await(held);w=job("W",writer);blocked(operation.equals("TRANSFER")?"hr_employee":"iam_user",ids.get("G"));}else{w=job("W",writer);await(held);g=job("G",()->grants.grantRole(input(u,r,id)));blocked(operation.equals("TRANSFER")?"hr_employee":"iam_user",ids.get("W"));}release.countDown();var gr=result(g);var wr=result(w);
        boolean gOk=!grantFirst||commit,wOk=grantFirst||commit;assertThat(gr).isInstanceOf(gOk?UserRoleView.class:RuntimeException.class);assertThat(wr).isInstanceOf(wOk?(operation.equals("TRANSFER")?AssignmentChanged.class:UserView.class):RuntimeException.class);
        assertThat(queries.findUserRole(company,id).isPresent()).isEqualTo(gOk);assertThat(row("iam_role",r.roleId())).isEqualTo(rrow);
        if(operation.equals("TRANSFER")){assertThat(row("iam_user",u.userId())).isEqualTo(urow);assertThat(row("hr_employee",u.employeeId()).get("version")).isEqualTo(wOk?1L:0L);assertThat(row("hr_employee",u.employeeId()).get("auth_revocation_generation")).isEqualTo(erow.get("auth_revocation_generation"));if(wOk){assertThat(history(u)).hasSize(2);assertThat(history(u).getLast().get("position_id")).isEqualTo(position2);}else assertThat(history(u)).isEqualTo(hrow);}
        else{assertThat(row("hr_employee",u.employeeId())).isEqualTo(erow);assertThat(history(u)).isEqualTo(hrow);assertThat(row("iam_user",u.userId()).get("version")).isEqualTo(u.version()+(wOk?1:0));assertThat(row("iam_user",u.userId()).get("auth_revocation_generation")).isEqualTo(((Number)urow.get("auth_revocation_generation")).longValue()+(wOk&&operation.equals("DISABLE")?1:0));}
        trace("T32 "+operation+" grantFirst="+grantFirst+" commit="+commit,ids);
    }

    @Test void t32_employeeShareBlocksBareNonKeyVersionUpdate(){
        var u=user("bare.version");var r=role("R");var before=row("hr_employee",u.employeeId());var held=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();
        doAnswer(c->{c.callRealMethod();ids.put("G",physical());held.countDown();await(release);return null;}).when(grantStore).flush();
        var g=job("G",()->grant(u,r));await(held);var bare=job("bare",()->tx(()->{ids.put("bare",physical());jdbc.update("update hr_employee set version=version+1 where id=?",u.employeeId());throw new IllegalStateException("bare writer rollback");}));blocked("update hr_employee set version",ids.get("G"));release.countDown();assertThat(result(g)).isInstanceOf(UserRoleView.class);assertThat(result(bare)).isInstanceOf(RuntimeException.class);assertThat(row("hr_employee",u.employeeId())).isEqualTo(before);assertThat(sql(ids.get("bare"))).doesNotContain("FOR UPDATE","FOR SHARE");trace("T32 bare Employee non-key UPDATE",ids);
    }

    static final class MidnightClock extends Clock {
        final AtomicBoolean after=new AtomicBoolean();final AtomicInteger calls=new AtomicInteger();
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}public Clock withZone(ZoneId z){return this;}
        public Instant instant(){calls.incrementAndGet();return Instant.parse(after.get()?"2026-10-11T15:00:01Z":"2026-10-11T14:59:59Z");}
    }
    @ParameterizedTest @ValueSource(strings={"grantWait","termination","registeredToday","transferredToday"})
    void t30_t31_midnightKeepsActualPostLockDateAndSameDayRules(String scenario){
        var u=user("midnight",scenario.equals("registeredToday")?today:joined);var r=role("R");
        if(scenario.equals("transferredToday"))transfers.changeAssignment(transfer(u));
        var eb=row("hr_employee",u.employeeId());var hb=history(u);var ub=row("iam_user",u.userId());var rb=row("iam_role",r.roleId());var rootClock=new MidnightClock();var held=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();
        if(scenario.equals("grantWait")){
            var seen=new AtomicReference<LocalDate>();
            doAnswer(c->{var facts=spy((EmployeeLinkFacts)c.callRealMethod());doAnswer(v->{assertThat(rootClock.after).isTrue();seen.set(v.getArgument(1));assertThat(v.getArgument(1,LocalDate.class)).isEqualTo(today);return v.callRealMethod();}).when(facts).requireGrantableEmployee(any(),any());return facts;}).when(links).lockForLink(any());
            var locker=job("L",()->tx(()->{ids.put("L",physical());jdbc.queryForObject("select id from hr_employee where id=? FOR UPDATE",UUID.class,u.employeeId());held.countDown();await(release);throw new IllegalStateException("read lock fixture rollback");}));await(held);
            var custom=new UserRoleService(grantStore,organization,guard,manager,rootClock,30);
            var g=job("G",()->custom.grantRole(input(u,r,UUID.randomUUID())));blocked("hr_employee",ids.get("L"));rootClock.after.set(true);release.countDown();assertThat(result(locker)).isInstanceOf(RuntimeException.class);assertThat(result(g)).isInstanceOf(UserRoleView.class);assertThat(seen.get()).isEqualTo(today);assertThat(row("hr_employee",u.employeeId())).isEqualTo(eb);assertThat(history(u)).isEqualTo(hb);
        }else{
            doAnswer(c->{c.callRealMethod();ids.put("G",physical());held.countDown();await(release);return null;}).when(grantStore).flush();
            var g=job("G",()->grant(u,r));await(held);
            var custom=new EmployeeTerminationService(hrStore,hrLocks,organization,manager,rootClock,30);
            var h=job("H",()->custom.terminateEmployee(end(u)));blocked("hr_employee",ids.get("G"));rootClock.after.set(true);release.countDown();assertThat(result(g)).isInstanceOf(UserRoleView.class);var hResult=result(h);
            if(scenario.equals("termination")){assertThat(hResult).isInstanceOf(EmployeeTerminated.class);assertThat(row("hr_employee",u.employeeId()).get("termination_effective_on")).isEqualTo(java.sql.Date.valueOf(today));assertThat(history(u).getLast().get("effective_to")).isEqualTo(java.sql.Date.valueOf(today));}
            else{assertThat(hResult).isInstanceOfSatisfying(HrFailure.class,f->assertThat(f.code()).isEqualTo(scenario.equals("registeredToday")?HrFailure.Code.SAME_DAY_TERMINATION_UNSUPPORTED:HrFailure.Code.SAME_DAY_ASSIGNMENT_TERMINATION_UNSUPPORTED));assertThat(row("hr_employee",u.employeeId())).isEqualTo(eb);assertThat(history(u)).isEqualTo(hb);}
        }
        assertThat(rootClock.calls).hasValue(1);assertThat(row("iam_user",u.userId())).isEqualTo(ub);assertThat(row("iam_role",r.roleId())).isEqualTo(rb);System.out.println("I3 T30/T31 midnight "+scenario+" Root captured 2026-10-11 23:59:59 KST; test observation advanced to 12th; post-lock predicate/effectiveDate uses 11th; Clock calls="+rootClock.calls);
    }

    @ParameterizedTest @ValueSource(strings={"timeout","cancel","connection"})
    void t33_actualRoleWaitFailuresReleaseEmployeeShare(String mode){
        var u=user("failure");var r=role("R");var held=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();UUID id=UUID.randomUUID();
        var locker=job("L",()->tx(()->{ids.put("L",physical());jdbc.queryForObject("select id from iam_role where id=? FOR UPDATE",UUID.class,r.roleId());held.countDown();await(release);return true;}));await(held);
        doAnswer(c->{c.callRealMethod();ids.put("G",physical());return null;}).when(grantStore).requireWritableTransaction();
        doAnswer(c->{c.callRealMethod();if(mode.equals("timeout"))jdbc.execute("set local lock_timeout='1500ms'");return null;}).when(grantStore).boundRootWaits(anyInt());
        var failedStatement=new AtomicReference<Throwable>();doAnswer(c->{try{return c.callRealMethod();}catch(Throwable failure){failedStatement.set(failure);throw failure;}}).when(grantStore).lockRoleShare(any(),any());
        var g=job("G",()->grants.grantRole(input(u,r,id)));blocked("iam_role",ids.get("L"));
        var h=job("H",()->terminations.terminateEmployee(end(u)));blocked("hr_employee",ids.get("G"));
        if(!mode.equals("timeout"))assertThat(jdbc.queryForObject("select "+(mode.equals("cancel")?"pg_cancel_backend":"pg_terminate_backend")+"(?)",Boolean.class,ids.get("G").get("pid"))).isTrue();
        var error=result(g);assertThat(error).isInstanceOf(RuntimeException.class).isNotInstanceOf(IamFailure.class);var state=pg(failedStatement.get()).getSQLState();
        if(mode.equals("connection"))assertThat((Throwable)error).hasRootCauseInstanceOf(java.sql.SQLException.class);else assertThat(pg((Throwable)error).getSQLState()).isEqualTo(state);
        System.out.println("I3 T33 original locking statement SQLSTATE="+state+"; completion exception="+error.getClass().getName()+"; terminated connections can also fail rollback");
        if(mode.equals("timeout"))assertThat(state).isEqualTo("55P03");else if(mode.equals("cancel"))assertThat(state).isEqualTo("57014");else assertThat(state).isIn("57P01","08006","08003");
        assertThat(result(h)).isInstanceOf(EmployeeTerminated.class);assertThat(locker.isDone()).isFalse();assertThat(queries.findUserRole(company,id)).isEmpty();release.countDown();assertThat(result(locker)).isEqualTo(true);assertThat(row("iam_role",r.roleId()).get("status")).isEqualTo("ACTIVE");
        trace("T33 actual "+mode+" SQLSTATE="+state+" grant rollback releases Employee; HR finishes while Role locker still held",ids);
    }

    @Test void t33_realDeadlockIsTechnicalWithOneVictimAndNoRetry(){
        var u=user("deadlock");var r=role("R");var q=role("Q");var held=gate();var reverseHeld=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();UUID grantId=UUID.randomUUID();
        doAnswer(c->{c.callRealMethod();ids.put("G",physical());jdbc.execute("set local deadlock_timeout='100ms'");held.countDown();await(release);jdbc.queryForObject("select id from iam_role where id=? FOR UPDATE",UUID.class,q.roleId());return null;}).when(grantStore).flush();
        var g=job("G",()->grants.grantRole(input(u,r,grantId)));await(held);
        var reverse=job("reverse",()->tx(()->{ids.put("reverse",physical());jdbc.execute("set local deadlock_timeout='100ms'");jdbc.queryForObject("select id from iam_role where id=? FOR UPDATE",UUID.class,q.roleId());reverseHeld.countDown();jdbc.queryForObject("select id from iam_role where id=? FOR UPDATE",UUID.class,r.roleId());return true;}));await(reverseHeld);blocked("iam_role",ids.get("G"));release.countDown();var a=result(g);var b=result(reverse);
        var failures=List.of(a,b).stream().filter(RuntimeException.class::isInstance).map(RuntimeException.class::cast).toList();assertThat(failures).hasSize(1);assertThat(pg(failures.getFirst()).getSQLState()).isEqualTo("40P01");assertThat(grantStore.translate(failures.getFirst())).isSameAs(failures.getFirst());assertThat(queries.findUserRole(company,grantId).isPresent()).isEqualTo(a instanceof UserRoleView);assertThat(row("iam_role",r.roleId()).get("version")).isEqualTo(r.version());assertThat(row("iam_role",q.roleId()).get("version")).isEqualTo(q.version());trace("T33 actual deadlock from test-only reverse locker, one victim, no auto retry",ids);
    }

    @ParameterizedTest @ValueSource(strings={"grant","revoke","deactivate"})
    void t33_actualCommitAbortAfterFlushPreservesAllRows(String operation){
        var u=user("commit.abort");var r=role("R");var g=operation.equals("grant")?null:grant(u,r);var oldRole=row("iam_role",r.roleId());var oldGrants=table("iam_user_role");var originals=originals();
        jdbc.execute("create function i3_abort_commit() returns trigger language plpgsql as $$ begin raise exception 'test actual commit abort' using errcode='23514'; return null; end $$");
        jdbc.execute("create constraint trigger i3_abort after insert or update on iam_user_role deferrable initially deferred for each row execute function i3_abort_commit()");
        var flushed=new AtomicInteger();doAnswer(c->{c.callRealMethod();flushed.incrementAndGet();return null;}).when(grantStore).flush();doAnswer(c->{c.callRealMethod();flushed.incrementAndGet();return null;}).when(roleStore).flush();
        try{var error=catchThrowable(()->{switch(operation){case "grant"->grant(u,r);case "revoke"->grants.revokeUserRole(new RevokeUserRole(company,g.grantId(),0L,"manual"));default->roles.deactivateRole(deactivate(r));}});assertThat(flushed).hasPositiveValue();assertThat(pg(error).getSQLState()).isEqualTo("23514");assertThat(error).isNotInstanceOf(IamFailure.class);assertThat(row("iam_role",r.roleId())).isEqualTo(oldRole);assertThat(table("iam_user_role")).isEqualTo(oldGrants);unchanged(originals);}
        finally{jdbc.execute("drop trigger i3_abort on iam_user_role");jdbc.execute("drop function i3_abort_commit()");}
        System.out.println("I3 T33 "+operation+" successful flush then actual deferred commit abort; fresh rows/versions restored, not UNKNOWN_OUTCOME");
    }

    @ParameterizedTest @ValueSource(ints={16,128})
    void t38_measuredBoundedGrantSetSuccessAndRollback(int size){
        var r=role("SCALE");var rollbackRole=role("ROLLBACK");var expected=new HashSet<UUID>();
        for(int i=0;i<size;i++){var u=user("scale."+i);expected.add(grant(u,r).grantId());grant(u,rollbackRole);}
        var rootId=new AtomicReference<Map<String,Object>>();var lockAt=new AtomicLong();
        doAnswer(c->{var value=c.callRealMethod();rootId.set(physical());lockAt.set(System.nanoTime());return value;}).when(roleStore).lockRole(any(),any());
        var memory=java.lang.management.ManagementFactory.getMemoryMXBean();long heapBefore=memory.getHeapMemoryUsage().getUsed();long start=System.nanoTime();roles.deactivateRole(deactivate(r));long elapsed=System.nanoTime()-start,lockUpper=System.nanoTime()-lockAt.get(),heapDelta=memory.getHeapMemoryUsage().getUsed()-heapBefore;
        assertThat(jdbc.queryForList("select id from iam_user_role where role_id=? and revoke_kind='ROLE_DEACTIVATION' and version=1",UUID.class,r.roleId())).containsExactlyInAnyOrderElementsOf(expected);
        var before=table("iam_user_role");var rb=row("iam_role",rollbackRole.roleId());
        doAnswer(c->{c.callRealMethod();throw new IllegalStateException("scale whole flush rollback");}).when(roleStore).flush();
        long rollbackStart=System.nanoTime();assertThatThrownBy(()->roles.deactivateRole(deactivate(rollbackRole))).isInstanceOf(RuntimeException.class);long rollbackCost=System.nanoTime()-rollbackStart;assertThat(table("iam_user_role")).isEqualTo(before);assertThat(row("iam_role",rollbackRole.roleId())).isEqualTo(rb);
        System.out.println("I3 T38 measured N="+size+" success_ms="+elapsed/1_000_000.0+" lock_acquired_to_return_upper_ms="+lockUpper/1_000_000.0+" heap_used_delta_bytes="+heapDelta+" full_flush_rollback_ms="+rollbackCost/1_000_000.0+" PostgreSQL="+jdbc.queryForObject("show server_version",String.class)+" Java="+System.getProperty("java.version")+" heap_max="+memory.getHeapMemoryUsage().getMax()+" processors="+Runtime.getRuntime().availableProcessors()+" SQL logging enabled. Heap delta is not per-request peak; no production maximum/SLA claim. Exact target IDs="+expected);
    }
}
