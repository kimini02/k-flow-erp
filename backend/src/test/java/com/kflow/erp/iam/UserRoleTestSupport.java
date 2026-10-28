package com.kflow.erp.iam;

import com.kflow.erp.KFlowErpApplication;
import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.application.*;
import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.application.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.junit.jupiter.api.*;
import org.kflow.testprobe.ManagedProbe;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
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
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers @SpringBootTest(classes=KFlowErpApplication.class)
@Import(UserRoleTestSupport.ProbeConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS) @Timeout(90)
abstract class UserRoleTestSupport {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16")
        .withInitScript("org03-probe.sql").withCommand("postgres","-c","log_statement=all","-c","log_line_prefix=%p %x ");
    @TestConfiguration @EntityScan(basePackageClasses={KFlowErpApplication.class,ManagedProbe.class}) static class ProbeConfiguration{}
    @Autowired UserRoleService service;
    @Autowired UserRoleQueryService queries;
    @MockitoSpyBean UserRoleStore userRoles;
    @Autowired RoleService roles;
    @Autowired RoleQueryService roleQueries;
    @MockitoSpyBean RoleStore roleStore;
    @Autowired UserService users;
    @Autowired UserQueryService userQueries;
    @MockitoSpyBean IamStore userStore;
    @Autowired EmployeeAssignmentService transfers;
    @Autowired EmployeeTerminationService terminations;
    @Autowired OrganizationAssignmentGuard orgGuard;
    @Autowired OrganizationStateService states;
    @MockitoSpyBean OrganizationService organization;
    @MockitoSpyBean EmployeeRoleGrantGuard guard;
    @MockitoSpyBean HrAccountLinkStore links;
    @MockitoSpyBean HrAssignmentStore hrLocks;
    @MockitoSpyBean HrStore hrStore;
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
        for(String t:List.of("iam_user_role","iam_role","iam_user","hr_employee_assignment","hr_employee","organization_site","organization_department","organization_position"))jdbc.update("delete from "+t);
        jdbc.update("delete from org03_probe.org03_test_probe");
        company=organization.getCompany().companyId();
        site=organization.registerSite("S","site").siteId();site2=organization.registerSite("S2","other").siteId();
        department=organization.registerDepartment("D","department").departmentId();department2=organization.registerDepartment("D2","other").departmentId();
        position=organization.registerPosition("P","position").positionId();position2=organization.registerPosition("P2","other").positionId();
        pool=Executors.newFixedThreadPool(6);gates=new ArrayList<>();
    }
    @AfterEach void cleanup()throws Exception{gates.forEach(CountDownLatch::countDown);pool.shutdownNow();assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
    EmployeeCreated employee(String no){
        var registration=new EmployeeRegistrationService(hrStore,orgGuard,manager,Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"),ZoneOffset.UTC));
        var e=registration.createEmployee(new CreateEmployee(company,no,"synthetic employee",joined,site,department,position,"original reason"));
        assertThat(employeeRow(e.employee().id()).get("version")).isEqualTo(0L);assertThat(history(e.employee().id()).getFirst().get("version")).isEqualTo(0L);
        System.out.println("I3 baseline actual registration commit 2026-10-01 Employee="+e.employee().id()+"; independent IAM/HR Clock 2026-10-11");return e;
    }
    UserView user(String name){var e=employee(name.replace('.','-').toUpperCase(Locale.ROOT));return users.createUser(new CreateUser(company,e.employee().id(),"u."+name.toLowerCase(Locale.ROOT),"creation reason"));}
    RoleView inactiveRole(String code){return roles.createRole(new CreateRole(company,code,"role "+code,"initial reason"));}
    RoleView role(String code){var r=inactiveRole(code);return roles.activateRole(change(r,0));}
    ChangeRoleStatus change(RoleView r,long version){return new ChangeRoleStatus(company,r.roleId(),version,"state reason");}
    ChangeUserStatus change(UserView u,long version){return new ChangeUserStatus(company,u.userId(),version,"user state reason");}
    GrantRole grant(UserView u,RoleView r){return new GrantRole(company,UUID.randomUUID(),u.userId(),r.roleId(),"grant reason");}
    TerminateEmployee end(UserView u){return new TerminateEmployee(company,u.employeeId(),0L,today,"termination reason");}
    ChangeAssignment transfer(UserView u){return new ChangeAssignment(company,u.employeeId(),0L,today,site2,department2,position2,"transfer reason");}
    Map<String,Object> grantRow(UUID id){return jdbc.queryForMap("select * from iam_user_role where id=?",id);}
    Map<String,Object> roleRow(UUID id){return jdbc.queryForMap("select * from iam_role where id=?",id);}
    Map<String,Object> userRow(UUID id){return jdbc.queryForMap("select * from iam_user where id=?",id);}
    Map<String,Object> employeeRow(UUID id){return jdbc.queryForMap("select * from hr_employee where id=?",id);}
    List<Map<String,Object>> history(UUID id){return jdbc.queryForList("select * from hr_employee_assignment where employee_id=? order by effective_from,id",id);}
    List<Map<String,Object>> rows(String table){return jdbc.queryForList("select * from "+table+" order by id");}
    Map<String,List<Map<String,Object>>> baseline(){var out=new LinkedHashMap<String,List<Map<String,Object>>>();for(String t:List.of("organization_company","organization_site","organization_department","organization_position","hr_employee","hr_employee_assignment","iam_user"))out.put(t,rows(t));return out;}
    void unchanged(Map<String,List<Map<String,Object>>> before){before.forEach((t,r)->assertThat(rows(t)).as(t).isEqualTo(r));}
    Set<UUID> unrevoked(UUID role){return new LinkedHashSet<>(jdbc.queryForList("select id from iam_user_role where role_id=? and revoked_at is null order by id",UUID.class,role));}
    void failure(IamFailure.Code code,Runnable r){assertThatThrownBy(r::run).isInstanceOfSatisfying(IamFailure.class,e->assertThat(e.code()).isEqualTo(code));}
    void hrFailure(HrFailure.Code code,Runnable r){assertThatThrownBy(r::run).isInstanceOfSatisfying(HrFailure.class,e->assertThat(e.code()).isEqualTo(code));}
    PSQLException pg(Throwable f){for(Throwable c=f;c!=null;c=c.getCause())if(c instanceof PSQLException p)return p;throw new AssertionError("Expected PostgreSQL cause",f);}
    <T>T tx(Supplier<T> a){return new TransactionTemplate(manager).execute(s->a.get());}
    CountDownLatch gate(){var g=new CountDownLatch(1);gates.add(g);return g;}
    void await(CountDownLatch g){try{assertThat(g.await(15,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    Future<Object> job(String name,Supplier<?> a){return pool.submit(()->{String old=Thread.currentThread().getName();Thread.currentThread().setName(name);try{return a.get();}catch(RuntimeException e){System.out.println("I3 worker "+name+" failure "+e);e.printStackTrace(System.out);return e;}finally{Thread.currentThread().setName(old);}});}
    Object result(Future<Object> f){try{return f.get(20,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}}
    boolean thread(String name){return Thread.currentThread().getName().equals(name);}
    Map<String,Object> physical(){return em.unwrap(Session.class).doReturningWork(c->{try(var s=c.createStatement();var r=s.executeQuery("select pg_backend_pid(),pg_current_xact_id()::text")){r.next();return Map.of("pid",r.getInt(1),"xid",r.getString(2));}});}
    String sql(Map<String,Object> id){String key=id.get("pid")+" "+id.get("xid")+" ";return postgres.getLogs().lines().filter(l->l.contains(key)).reduce("",(a,b)->a+b+"\n");}
    Map<String,Object> blocked(String query,Map<String,Object> blocker){
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime()<until){
            var rows=jdbc.queryForList("select pid,pg_blocking_pids(pid)::text blockers,query from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and cardinality(pg_blocking_pids(pid))>0 and query ilike ?","%"+query+"%");
            for(var r:rows)if(blocker==null||Arrays.asList(r.get("blockers").toString().replace("{","").replace("}","").split(",")).contains(blocker.get("pid").toString())){
                assertThat(r.get("pid")).isNotEqualTo(blocker==null?null:blocker.get("pid"));System.out.println("I3 actual PG blocker "+r);return r;
            }
            try{Thread.sleep(15);}catch(InterruptedException e){throw new AssertionError(e);}
        }throw new AssertionError("No actual PostgreSQL blocker: "+query);
    }
}
