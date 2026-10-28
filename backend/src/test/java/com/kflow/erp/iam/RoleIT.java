package com.kflow.erp.iam;

import com.kflow.erp.KFlowErpApplication;
import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.domain.User;
import com.kflow.erp.iam.domain.Role;
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
@Import(RoleIT.ProbeConfiguration.class) @Timeout(60)
class RoleIT {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16")
        .withInitScript("org03-probe.sql").withCommand("postgres","-c","log_statement=all","-c","log_line_prefix=%p %x ");
    @TestConfiguration @EntityScan(basePackageClasses={KFlowErpApplication.class,ManagedProbe.class}) static class ProbeConfiguration{}
    @Autowired RoleService service;
    @Autowired RoleQueryService queries;
    @MockitoSpyBean RoleStore store;
    @Autowired UserRoleStore userRoleStore;
    @MockitoSpyBean OrganizationService organization;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @MockitoBean(name="iamClock") Clock clock;
    final Instant now=Instant.parse("2026-10-11T03:00:00Z");
    UUID company;ExecutorService pool;List<CountDownLatch> gates;
    @BeforeEach void setup(){
        when(clock.instant()).thenReturn(now);jdbc.update("delete from iam_role");
        for(String t:List.of("iam_user","hr_employee_assignment","hr_employee","organization_site","organization_department","organization_position"))jdbc.update("delete from "+t);jdbc.update("delete from org03_probe.org03_test_probe");
        company=organization.getCompany().companyId();pool=Executors.newFixedThreadPool(4);gates=new ArrayList<>();
    }
    @AfterEach void cleanup()throws Exception{gates.forEach(CountDownLatch::countDown);pool.shutdownNow();assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
    CreateRole create(String code){return new CreateRole(company,code,"same name","  initial reason  ");}
    ChangeRoleStatus change(RoleView r,long version){return new ChangeRoleStatus(company,r.roleId(),version,"  state reason  ");}
    RoleView action(String kind,RoleView r,long version){return switch(kind){
        case "create"->service.createRole(create("CREATED"));
        case "rename"->service.renameRole(new RenameRole(company,r.roleId(),"renamed",version,"rename reason"));
        case "renameOther"->service.renameRole(new RenameRole(company,r.roleId(),"other name",version,"other reason"));
        case "renameNoop"->service.renameRole(new RenameRole(company,r.roleId(),r.name(),version,"ignored reason"));
        case "activate"->service.activateRole(change(r,version));
        case "deactivate"->service.deactivateRole(change(r,version));
        default->throw new IllegalArgumentException(kind);
    };}
    Map<String,Object> row(UUID id){return jdbc.queryForMap("select * from iam_role where id=?",id);}
    int count(){return jdbc.queryForObject("select count(*) from iam_role",Integer.class);}
    PSQLException pg(Throwable f){for(Throwable c=f;c!=null;c=c.getCause())if(c instanceof PSQLException p)return p;throw new AssertionError("Expected PostgreSQL cause",f);}
    void sqlFailure(String state,String constraint,Runnable r){var error=catchThrowable(r::run);var p=pg(error);assertThat(p.getSQLState()).isEqualTo(state);if(constraint!=null)assertThat(p.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
        assertThat(store.translate((RuntimeException)error)).isSameAs(error);System.out.println("I2 isolated SQLSTATE="+state+" constraint="+constraint+" remains technical");}
    void failure(IamFailure.Code code,Runnable r){assertThatThrownBy(r::run).isInstanceOfSatisfying(IamFailure.class,e->assertThat(e.code()).isEqualTo(code));}
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
                assertThat(r.get("pid")).isNotEqualTo(blocker==null?null:blocker.get("pid"));System.out.println("I2 actual PG blocker "+r);return;
            }
            try{Thread.sleep(15);}catch(InterruptedException e){throw new AssertionError(e);}
        }fail("No actual PostgreSQL blocker: "+query);
    }
    @Test void t01_t08_t09_t10_actualSqlDtoAndCommittedDb(){
        System.out.println("I2 actual runtime Java="+System.getProperty("java.version")+" SpringBoot="+org.springframework.boot.SpringBootVersion.getVersion()+" SpringFramework="+org.springframework.core.SpringVersion.getVersion()+" Hibernate="+org.hibernate.Version.getVersionString()+" PG="+jdbc.queryForObject("show server_version",String.class)+" image="+postgres.getDockerImageName());
        var ids=new ArrayList<Map<String,Object>>();var sameTx=new ArrayList<Map<String,Object>>();
        doAnswer(c->{c.callRealMethod();ids.add(physical());return null;}).when(store).requireWritableTransaction();
        doAnswer(c->{c.callRealMethod();assertThat(physical()).isEqualTo(ids.getLast());sameTx.add(jdbc.queryForMap("select * from iam_role"));return null;}).when(store).flush();
        var initial=service.createRole(create(" sales "));assertThat(initial.roleId().version()).isEqualTo(4);assertThat(initial.roleId().variant()).isEqualTo(2);
        assertThat(initial.code()).isEqualTo("SALES");assertThat(initial.status()).isEqualTo("INACTIVE");assertThat(initial.version()).isZero();assertThat(initial.creationReason()).isEqualTo("initial reason");assertThat(initial.lastChangeKind()).isNull();assertThat(initial.lastChangeReason()).isNull();assertThat(initial.createdAt()).isEqualTo(now);assertThat(initial.updatedAt()).isEqualTo(now);
        var current=initial;int n=0;
        for(String op:List.of("rename","activate","deactivate","renameOther","activate")){
            when(clock.instant()).thenReturn(now.plusSeconds(++n));current=action(op,current,current.version());
            assertThat(current.version()).isEqualTo(n);assertThat(current.updatedAt()).isEqualTo(now.plusSeconds(n));assertThat(current.createdAt()).isEqualTo(now);
            assertThat(current.roleId()).isEqualTo(initial.roleId());assertThat(current.code()).isEqualTo(initial.code());assertThat(current.companyId()).isEqualTo(company);assertThat(current.creationReason()).isEqualTo(initial.creationReason());
            assertThat(current.lastChangeKind()).isEqualTo(op.startsWith("rename")?"RENAME":op.toUpperCase(Locale.ROOT));
            assertThat(queries.findRole(company,current.roleId())).contains(current);assertThat(sameTx.getLast()).isEqualTo(row(current.roleId()));assertThat(sameTx.getLast().get("version")).isEqualTo(current.version());
        }
        assertThat(ids).hasSize(6);assertThat(sameTx).hasSize(6);
        for(int i=0;i<ids.size();i++){String observed=sql(ids.get(i));if(i==0)assertThat(observed).contains("insert into iam_role");else {var updates=observed.lines().filter(l->l.contains("update iam_role set")).toList();assertThat(updates).hasSize(1);assertThat(updates.getFirst()).contains("last_change_kind","last_change_reason","updated_at","version").containsPattern("where id=.+ and version=");assertThat(observed).contains("FOR UPDATE");}System.out.println("I2 T01/T08-10 PID/XID="+ids.get(i)+" same-Tx row="+sameTx.get(i)+"\n"+observed);}
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void t03_t04_uniqueInsertWaitCommitOrRollback(boolean commit){
        var entered=new CountDownLatch(2);var inserted=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();
        doAnswer(c->{ids.put(Thread.currentThread().getName(),physical());entered.countDown();await(entered);if(thread("B"))await(inserted);return c.callRealMethod();}).when(store).add(any());
        doAnswer(c->{c.callRealMethod();if(thread("A")){inserted.countDown();await(release);if(!commit)throw new IllegalStateException("first insert rollback");}return null;}).when(store).flush();
        var a=job("A",()->service.createRole(create("sales")));var b=job("B",()->service.createRole(create(" SALES ")));await(inserted);blocked("insert into iam_role",ids.get("A"));assertThat(b.isDone()).isFalse();release.countDown();var ra=result(a);var rb=result(b);
        assertThat(ids.get("A").get("pid")).isNotEqualTo(ids.get("B").get("pid"));assertThat(ids.get("A").get("xid")).isNotEqualTo(ids.get("B").get("xid"));
        if(commit){assertThat(ra).isInstanceOf(RoleView.class);assertThat(rb).isInstanceOfSatisfying(IamFailure.class,f->assertThat(f.code()).isEqualTo(ROLE_CODE_CONFLICT));assertThat(pg((Throwable)rb).getSQLState()).isEqualTo("23505");assertThat(pg((Throwable)rb).getServerErrorMessage().getConstraint()).isEqualTo("uq_iam_role_company_code");}else {assertThat(ra).isInstanceOf(RuntimeException.class);assertThat(rb).isInstanceOf(RoleView.class);}
        assertThat(count()).isEqualTo(1);assertThat(queries.findRoleByCode(company,"sales")).isPresent();System.out.println("I2 T03/T04 commit="+commit+" "+ids+" final one row; commit loser23505/uq_iam_role_company_code; rollback successor succeeds\n"+sql(ids.get("A"))+sql(ids.get("B")));
    }
    @ParameterizedTest @CsvSource({"INACTIVE,rename,renameOther","INACTIVE,activate,activate","ACTIVE,deactivate,deactivate","INACTIVE,rename,activate","INACTIVE,activate,rename","ACTIVE,rename,deactivate","ACTIVE,deactivate,rename","INACTIVE,deactivate,deactivate","ACTIVE,activate,activate","INACTIVE,renameNoop,renameNoop","INACTIVE,deactivate,activate","INACTIVE,activate,deactivate","ACTIVE,activate,deactivate","ACTIVE,deactivate,activate"})
    void t11_t12_t13_t14_orderedConcurrentCommands(String start,String firstOp,String secondOp){
        var created=service.createRole(create("R"));final var r=start.equals("ACTIVE")?service.activateRole(change(created,0)):created;var before=row(r.roleId());var held=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();
        doAnswer(c->{ids.put(Thread.currentThread().getName(),physical());var v=c.callRealMethod();if(thread("A")){held.countDown();await(release);}return v;}).when(store).lockRole(any(),any());
        var a=job("A",()->action(firstOp,r,r.version()));await(held);var b=job("B",()->action(secondOp,r,r.version()));blocked("iam_role",ids.get("A"));assertThat(b.isDone()).isFalse();release.countDown();var ra=(RoleView)result(a);var rb=result(b);
        boolean noop=ra.version()==r.version();if(noop)assertThat(rb).isInstanceOf(RoleView.class);else assertThat(rb).isInstanceOfSatisfying(IamFailure.class,f->assertThat(f.code()).isEqualTo(VERSION_CONFLICT));
        if(noop&&((RoleView)rb).version()==r.version()){assertThat(row(r.roleId())).isEqualTo(before);for(var id:ids.values())assertThat(sql(id)).doesNotContain("update iam_role");}
        else assertThat(row(r.roleId()).get("version")).isEqualTo(r.version()+1);
        assertThat(ids.get("A").get("pid")).isNotEqualTo(ids.get("B").get("pid"));assertThat(ids.get("A").get("xid")).isNotEqualTo(ids.get("B").get("xid"));System.out.println("I2 T11-14 "+start+" "+firstOp+" -> "+secondOp+" role="+r.roleId()+" "+ids+" final="+row(r.roleId()));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void t16_t19a_sameRoleWaitsForActualRootCompletion(boolean commit){
        var r=service.createRole(create("R"));var before=row(r.roleId());var held=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();
        doAnswer(c->{ids.put(Thread.currentThread().getName(),physical());return c.callRealMethod();}).when(store).lockRole(any(),any());
        doAnswer(c->{c.callRealMethod();if(thread("A")){assertThat(row(r.roleId()).get("version")).isEqualTo(1L);held.countDown();await(release);if(!commit)throw new IllegalStateException("post-update rollback");}return null;}).when(store).flush();
        var a=job("A",()->action("activate",r,0));await(held);assertThat(row(r.roleId())).isEqualTo(before);
        var b=job("B",()->action("rename",r,0));blocked("FOR UPDATE",ids.get("A"));assertThat(b.isDone()).isFalse();release.countDown();var ra=result(a);var rb=result(b);
        if(commit){assertThat(ra).isInstanceOf(RoleView.class);assertThat(rb).isInstanceOf(IamFailure.class);assertThat(row(r.roleId()).get("status")).isEqualTo("ACTIVE");}else {assertThat(ra).isInstanceOf(RuntimeException.class);assertThat(rb).isInstanceOf(RoleView.class);assertThat(row(r.roleId()).get("status")).isEqualTo("INACTIVE");assertThat(row(r.roleId()).get("last_change_kind")).isEqualTo("RENAME");}
        assertThat(row(r.roleId()).get("version")).isEqualTo(1L);for(var id:ids.values()){assertThat(sql(id)).contains("FOR UPDATE");System.out.println(sql(id));}System.out.println("I2 T19a role="+r.roleId()+" commit="+commit+" distinct roots="+ids+" lock released; final="+row(r.roleId()));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void t19b_differentRoleCommitsWhileFirstLockHeld(boolean commit){
        var r=service.createRole(create("R"));var s=service.createRole(create("S"));var held=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();
        doAnswer(c->{ids.put(Thread.currentThread().getName(),physical());return c.callRealMethod();}).when(store).lockRole(any(),any());
        doAnswer(c->{c.callRealMethod();if(thread("A")){held.countDown();await(release);if(!commit)throw new IllegalStateException("first rollback");}return null;}).when(store).flush();
        var a=job("A",()->action("activate",r,0));await(held);var b=job("B",()->action("activate",s,0));assertThat(result(b)).isInstanceOf(RoleView.class);assertThat(a.isDone()).isFalse();assertThat(row(s.roleId()).get("version")).isEqualTo(1L);
        for(var id:ids.values())assertThat(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))",Integer.class,id.get("pid"))).isZero();
        release.countDown();var ra=result(a);assertThat(ra).isInstanceOf(commit?RoleView.class:RuntimeException.class);assertThat(row(r.roleId()).get("version")).isEqualTo(commit?1L:0L);
        System.out.println("I2 T19b R="+r.roleId()+" S="+s.roleId()+" S commit before releasing R; pg_blocking_pids empty; Rcommit="+commit+" roots="+ids+"\n"+sql(ids.get("A"))+sql(ids.get("B")));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void t19c_bareNonKeyUpdateBlockedWithoutPrelock(boolean commit){
        var r=service.createRole(create("R"));var held=gate();var release=gate();var ids=new ConcurrentHashMap<String,Map<String,Object>>();
        doAnswer(c->{ids.put("A",physical());var v=c.callRealMethod();held.countDown();await(release);if(!commit)throw new IllegalStateException("first rollback");return v;}).when(store).lockRole(any(),any());
        var a=job("A",()->action("activate",r,0));await(held);
        var b=job("bare",()->tx(()->{ids.put("bare",physical());jdbc.update("update iam_role set status='INACTIVE',version=version+1 where id=?",r.roleId());throw new IllegalStateException("isolated bare writer rollback");}));
        blocked("update iam_role set status",ids.get("A"));assertThat(b.isDone()).isFalse();release.countDown();var ra=result(a);assertThat(ra).isInstanceOf(commit?RoleView.class:RuntimeException.class);assertThat(result(b)).isInstanceOf(RuntimeException.class);
        assertThat(sql(ids.get("A"))).contains("FOR UPDATE");assertThat(sql(ids.get("bare"))).contains("update iam_role set status").doesNotContain("FOR UPDATE","FOR SHARE");assertThat(row(r.roleId()).get("version")).isEqualTo(commit?1L:0L);
        System.out.println("I2 T19c role="+r.roleId()+" bare non-key UPDATE blocked, no prelock, writer rollback; firstCommit="+commit+" roots="+ids+"\n"+sql(ids.get("A"))+sql(ids.get("bare")));
    }
    @ParameterizedTest @ValueSource(strings={"create","rename","activate","deactivate"})
    void t16_flushFailureRestoresAllFields(String op){
        var created=op.equals("create")?null:service.createRole(create("R"));var r=op.equals("deactivate")?service.activateRole(change(created,0)):created;
        var before=r==null?null:row(r.roleId());var seen=new AtomicBoolean();
        doAnswer(c->{c.callRealMethod();seen.set(true);assertThat(count()).isEqualTo(1);throw new IllegalStateException("test post-flush rollback");}).when(store).flush();
        assertThatThrownBy(()->action(op,r,r==null?0:r.version())).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("test post-flush rollback");assertThat(seen).isTrue();
        if(r==null)assertThat(count()).isZero();else assertThat(row(r.roleId())).isEqualTo(before);
        System.out.println("I2 T16 confirmed post-flush rollback "+op+" complete values/version/reason/times restored via new connection");
    }
    @ParameterizedTest @ValueSource(strings={"create","rename","activate","deactivate"})
    void t18_t26_realCommitAbortReturnsNoSuccess(String op){
        var created=op.equals("create")?null:service.createRole(create("R"));var r=op.equals("deactivate")?service.activateRole(change(created,0)):created;var before=r==null?null:row(r.roleId());
        jdbc.execute("create function iam02_abort_commit() returns trigger language plpgsql as $$ begin raise exception 'test commit abort' using errcode='23514'; return null; end $$");
        jdbc.execute("create constraint trigger iam02_abort after insert or update on iam_role deferrable initially deferred for each row execute function iam02_abort_commit()");
        var flushed=new AtomicBoolean();doAnswer(c->{c.callRealMethod();flushed.set(true);return null;}).when(store).flush();
        try{var f=catchThrowable(()->action(op,r,r==null?0:r.version()));assertThat(flushed).isTrue();assertThat(pg(f).getSQLState()).isEqualTo("23514");assertThat(f).isNotInstanceOf(IamFailure.class);if(r==null)assertThat(count()).isZero();else assertThat(row(r.roleId())).isEqualTo(before);}
        finally{jdbc.execute("drop trigger iam02_abort on iam_role");jdbc.execute("drop function iam02_abort_commit()");}
        System.out.println("I2 T18/T26 "+op+" actual deferred commit abort23514 after successful flush; no DTO returned, full rollback");
    }
    RoleService withManager(PlatformTransactionManager m){return new RoleService(store,userRoleStore,organization,m,clock,30);}
    @Test void t15_actualCommitThenAckWrapperWithRetry(){
        var commits=new AtomicInteger();var rolls=new AtomicInteger();var wrapped=withManager(new PlatformTransactionManager(){
            public TransactionStatus getTransaction(TransactionDefinition d){return manager.getTransaction(d);}
            public void commit(TransactionStatus s){manager.commit(s);commits.incrementAndGet();throw new TransactionSystemException("test lost ACK after actual commit");}
            public void rollback(TransactionStatus s){rolls.incrementAndGet();manager.rollback(s);}
        });
        assertThatThrownBy(()->wrapped.createRole(create("R"))).isInstanceOf(TransactionSystemException.class);var r=queries.findRoleByCode(company,"r").orElseThrow();assertThat(count()).isEqualTo(1);
        failure(ROLE_CODE_CONFLICT,()->service.createRole(create("r")));
        assertThatThrownBy(()->wrapped.renameRole(new RenameRole(company,r.roleId(),"renamed",0L,"rename"))).isInstanceOf(TransactionSystemException.class);failure(VERSION_CONFLICT,()->service.renameRole(new RenameRole(company,r.roleId(),"renamed",0L,"retry")));
        assertThatThrownBy(()->wrapped.activateRole(change(r,1))).isInstanceOf(TransactionSystemException.class);failure(VERSION_CONFLICT,()->service.activateRole(change(r,1)));
        assertThatThrownBy(()->wrapped.deactivateRole(change(r,2))).isInstanceOf(TransactionSystemException.class);failure(VERSION_CONFLICT,()->service.deactivateRole(change(r,2)));
        assertThat(service.deactivateRole(change(r,3)).version()).isEqualTo(3);assertThat(commits.get()).isEqualTo(4);assertThat(rolls.get()).isZero();assertThat(count()).isEqualTo(1);
        System.out.println("I2 T15 actual 4 commits then test ACK wrapper, no compensation/retry, stale version/code retry rejected. Not real network or receipt proof; unknown outcome is not called rollback");
    }
    @ParameterizedTest @ValueSource(longs={0,1})
    void t17_staleManagedRoleLocallyRefreshedWithoutProbeFlush(long expected){
        var r=service.createRole(create("R"));UUID probe=UUID.randomUUID();jdbc.update("insert into org03_probe.org03_test_probe values(?,'before')",probe);
        var loaded=gate();var go=gate();var stale=new AtomicReference<Role>();var unrelated=new AtomicReference<ManagedProbe>();var mode=new AtomicReference<jakarta.persistence.FlushModeType>();
        doAnswer(c->{if(thread("stale")){stale.set(em.find(Role.class,r.roleId()));var p=em.find(ManagedProbe.class,probe);unrelated.set(p);mode.set(em.getFlushMode());p.label("after");loaded.countDown();await(go);}return now;}).when(clock).instant();
        doAnswer(c->{var value=c.callRealMethod();if(thread("stale")){assertThat(value).isSameAs(stale.get());assertThat(((Role)value).facts().version()).isEqualTo(1);assertThat(em.contains(unrelated.get())).isTrue();assertThat(em.getFlushMode()).isEqualTo(mode.get());assertThat(jdbc.queryForObject("select label from org03_probe.org03_test_probe where id=?",String.class,probe)).isEqualTo("before");}return value;}).when(store).lockRole(any(),any());
        var a=job("stale",()->action("rename",r,expected));await(loaded);action("activate",r,0);go.countDown();var result=result(a);
        if(expected==0)assertThat(result).isInstanceOfSatisfying(IamFailure.class,f->assertThat(f.code()).isEqualTo(VERSION_CONFLICT));else assertThat(result).isInstanceOfSatisfying(RoleView.class,v->{assertThat(v.version()).isEqualTo(2);assertThat(v.status()).isEqualTo("ACTIVE");});
        assertThat(jdbc.queryForObject("select label from org03_probe.org03_test_probe where id=?",String.class,probe)).isEqualTo(expected==0?"before":"after");System.out.println("I2 T17 locally refreshed stale Role; dirty unrelated probe not flushed/cleared/detached by lock; expected="+expected);
    }
    @ParameterizedTest @ValueSource(strings={"absent","readOnly","repeatable","wrongManager","nullRC","nullRR","nullReadOnly","metadataMismatch","connectionFailure"})
    void t18_physicalRootChecks(String mode){
        if(mode.equals("absent")){failure(TRANSACTION_BOUNDARY_VIOLATION,store::requireWritableTransaction);return;}
        PlatformTransactionManager m=mode.equals("wrongManager")?new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()):manager;
        var template=new TransactionTemplate(m);template.setIsolationLevel(mode.equals("repeatable")||mode.equals("nullRR")?TransactionDefinition.ISOLATION_REPEATABLE_READ:TransactionDefinition.ISOLATION_READ_COMMITTED);template.setReadOnly(mode.equals("readOnly")||mode.equals("nullReadOnly"));
        Runnable call=()->template.executeWithoutResult(tx->{Integer old=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();boolean ro=TransactionSynchronizationManager.isCurrentTransactionReadOnly();
            if(mode.startsWith("null")){TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);}
            if(mode.equals("metadataMismatch"))TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            if(mode.equals("connectionFailure")){int pid=(int)physical().get("pid");assertThat(result(job("killer",()->jdbc.queryForObject("select pg_terminate_backend(?)",Boolean.class,pid)))).isEqualTo(true);}
            try{store.requireWritableTransaction();if(mode.equals("nullRC"))System.out.println("I2 T18 metadata null but actual RC/off/autocommit=false same JPA connection "+physical());}
            finally{TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(old);TransactionSynchronizationManager.setCurrentTransactionReadOnly(ro);}});
        if(mode.equals("nullRC"))call.run();else if(mode.equals("connectionFailure"))assertThatThrownBy(call::run).isInstanceOf(RuntimeException.class);else failure(TRANSACTION_BOUNDARY_VIOLATION,call);
        assertThat(count()).isZero();
    }
    @Test void t18_allCommandsRejectAmbientAndWrongManagerBeforeCompany(){
        var r=service.createRole(create("R"));var wrong=withManager(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));clearInvocations(organization);
        for(String op:List.of("create","rename","activate","deactivate"))failure(TRANSACTION_BOUNDARY_VIOLATION,()->tx(()->action(op,r,0)));
        failure(TRANSACTION_BOUNDARY_VIOLATION,()->wrong.createRole(create("W")));failure(TRANSACTION_BOUNDARY_VIOLATION,()->wrong.renameRole(new RenameRole(company,r.roleId(),"new",0L,"reason")));failure(TRANSACTION_BOUNDARY_VIOLATION,()->wrong.activateRole(change(r,0)));failure(TRANSACTION_BOUNDARY_VIOLATION,()->wrong.deactivateRole(change(r,0)));
        verify(organization,never()).getCompany();assertThat(count()).isEqualTo(1);
    }
    @Test void t05_t06_t07_namespaceInputsAndReasonEvenForNoop(){
        String code="NS_"+UUID.randomUUID().toString().substring(0,8);var position=organization.registerPosition(code,"same name");var r=service.createRole(create(code));assertThat(r.roleId()).isNotEqualTo(position.positionId());
        failure(ROLE_CODE_CONFLICT,()->service.createRole(create(code.toLowerCase(Locale.ROOT))));action("activate",r,0);failure(ROLE_CODE_CONFLICT,()->service.createRole(create(code)));action("deactivate",r,1);failure(ROLE_CODE_CONFLICT,()->service.createRole(create(code)));
        for(String c:List.of("001","A-B","A_B","I","A".repeat(32),"ADMIN"))assertThat(service.createRole(create(c)).code()).isEqualTo(c);
        for(String bad:Arrays.asList(null,"","A".repeat(33),"\tA","A\n","\u00a0A","Ａ","한글","a.b","a@b","a b"))failure(INVALID_INPUT,()->service.createRole(create(bad)));
        var current=queries.findRole(company,r.roleId()).orElseThrow();var before=row(r.roleId());when(clock.instant()).thenReturn(now.plusSeconds(100));
        assertThat(service.deactivateRole(change(current,2))).isEqualTo(current);assertThat(service.renameRole(new RenameRole(company,r.roleId()," same name ",2L,"other"))).isEqualTo(current);assertThat(row(r.roleId())).isEqualTo(before);
        for(String bad:Arrays.asList(null,""," ","\u00a0","\u3000","x\n","x\t","x\u0085","\uD800","\uDC00","😀".repeat(501))){
            failure(INVALID_INPUT,()->service.createRole(new CreateRole(company,"BAD","name",bad)));
            failure(INVALID_INPUT,()->service.renameRole(new RenameRole(company,r.roleId(),r.name(),2L,bad)));
            failure(INVALID_INPUT,()->service.activateRole(new ChangeRoleStatus(company,r.roleId(),2L,bad)));
            failure(INVALID_INPUT,()->service.deactivateRole(new ChangeRoleStatus(company,r.roleId(),2L,bad)));
        }
        var emoji=service.createRole(new CreateRole(company,"EMOJI","😀".repeat(100),"😀".repeat(500)));assertThat(row(emoji.roleId()).get("name")).isEqualTo("😀".repeat(100));assertThat(emoji.creationReason()).isEqualTo("😀".repeat(500));
        for(String bad:Arrays.asList(null,"","\u00a0","\t","\uD800","😀".repeat(101))){failure(INVALID_INPUT,()->service.createRole(new CreateRole(company,"BAD",bad,"reason")));failure(INVALID_INPUT,()->service.renameRole(new RenameRole(company,r.roleId(),bad,2L,"reason")));}
        for(String valid:List.of("x","x".repeat(100),"😀".repeat(100))){var q=queries.findRole(company,r.roleId()).orElseThrow();service.renameRole(new RenameRole(company,r.roleId(),valid,q.version(),"😀".repeat(500)));}
        for(Long bad:Arrays.asList(null,-1L))failure(INVALID_INPUT,()->service.activateRole(new ChangeRoleStatus(company,r.roleId(),bad,"reason")));
        failure(ROLE_NOT_FOUND,()->service.activateRole(new ChangeRoleStatus(company,UUID.randomUUID(),0L,"reason")));
        assertThat(row(current.roleId()).get("status")).isEqualTo("INACTIVE");
    }
    @Test void t20_t22_exactConstraintsNullPairsAndTypeErrors(){
        var r=service.createRole(create("R"));var s=service.createRole(create("S"));
        for(String bad:List.of("lower","A B"," A","A ","A\n","\tA","한글","Ａ","A.B","@A","-A",""))sqlFailure("23514","ck_iam_role_code",()->jdbc.update("update iam_role set code=? where id=?",bad,r.roleId()));
        for(String[] pair:List.of(new String[]{"status='ENABLED'","ck_iam_role_status"},new String[]{"version=-1","ck_iam_role_version"},new String[]{"created_at='infinity'","ck_iam_role_timestamps"},new String[]{"updated_at='-infinity'","ck_iam_role_timestamps"}))sqlFailure("23514",pair[1],()->jdbc.update("update iam_role set "+pair[0]+" where id=?",r.roleId()));
        for(String field:List.of("name","creation_reason"))for(String bad:List.of(""," "," x","x "))sqlFailure("23514",field.equals("name")?"ck_iam_role_name":"ck_iam_role_creation_reason",()->jdbc.update("update iam_role set "+field+"=? where id=?",bad,r.roleId()));
        for(String invalid:List.of("last_change_reason='valid',last_change_kind=null","last_change_reason=null,last_change_kind='RENAME'","last_change_reason='',last_change_kind='RENAME'","last_change_reason=' ',last_change_kind='RENAME'","last_change_reason=' x',last_change_kind='RENAME'","last_change_reason='x ',last_change_kind='RENAME'","last_change_reason='valid',last_change_kind='OTHER'"))sqlFailure("23514","ck_iam_role_last_change",()->jdbc.update("update iam_role set "+invalid+" where id=?",r.roleId()));
        for(String field:List.of("id","company_id","code","name","status","version","creation_reason","created_at","updated_at"))sqlFailure("23502",null,()->jdbc.update("update iam_role set "+field+"=null where id=?",r.roleId()));
        for(String[] pair:List.of(new String[]{"code","33"},new String[]{"name","101"},new String[]{"creation_reason","501"},new String[]{"last_change_reason","501"}))sqlFailure("22001",null,()->jdbc.update("update iam_role set "+pair[0]+"=? where id=?","x".repeat(Integer.parseInt(pair[1])),r.roleId()));
        sqlFailure("23503","fk_iam_role_company",()->jdbc.update("update iam_role set company_id=? where id=?",UUID.randomUUID(),r.roleId()));
        sqlFailure("23505","pk_iam_role",()->jdbc.update("update iam_role set id=? where id=?",r.roleId(),s.roleId()));
        var duplicate=catchThrowable(()->jdbc.update("update iam_role set code='R' where id=?",s.roleId()));assertThat(pg(duplicate).getSQLState()).isEqualTo("23505");assertThat(pg(duplicate).getServerErrorMessage().getConstraint()).isEqualTo("uq_iam_role_company_code");assertThat(store.translate((RuntimeException)duplicate)).isInstanceOfSatisfying(IamFailure.class,f->assertThat(f.code()).isEqualTo(ROLE_CODE_CONFLICT));
        jdbc.execute("create unique index uq_iam02_test_other on iam_role(last_change_reason)");
        try{jdbc.update("update iam_role set last_change_reason='collision',last_change_kind='RENAME' where id=?",r.roleId());sqlFailure("23505","uq_iam02_test_other",()->jdbc.update("update iam_role set last_change_reason='collision',last_change_kind='RENAME' where id=?",s.roleId()));}finally{jdbc.execute("drop index uq_iam02_test_other");}
        for(String valid:List.of("x","x".repeat(500),"😀".repeat(500)))jdbc.update("update iam_role set creation_reason=?,last_change_reason=?,last_change_kind='RENAME' where id=?",valid,valid,r.roleId());
        // DB minimum contract intentionally differs from Java's complete Unicode validation.
        tx(()->{jdbc.update("update iam_role set name=?,creation_reason=? where id=?","\u00a0","\t",r.roleId());assertThat(row(r.roleId()).get("name")).isEqualTo("\u00a0");return null;});
        var fk=jdbc.queryForMap("select confupdtype,confdeltype,condeferrable from pg_constraint where conname='fk_iam_role_company'");assertThat(fk.get("confupdtype").toString()).isEqualTo("r");assertThat(fk.get("confdeltype").toString()).isEqualTo("r");assertThat(fk.get("condeferrable")).isEqualTo(false);
        System.out.println("I2 T20/T22 exact SQLSTATE+constraint checked, pair UNKNOWN rejected, varchar22001 separate; DB Unicode minimum != Java full rules; FK="+fk);
    }
    @Test void t21_companyValidationDistinctFromIsolatedFk(){
        UUID missing=UUID.randomUUID();for(Runnable call:List.<Runnable>of(()->service.createRole(new CreateRole(missing,"R","name","reason")),()->queries.findRole(missing,UUID.randomUUID()),()->queries.findRoleByCode(missing,"r"),()->queries.listRoles(missing,null,0,20)))assertThatThrownBy(call::run).isInstanceOfSatisfying(OrganizationFailure.class,f->assertThat(f.code()).isEqualTo(OrganizationFailure.Code.COMPANY_NOT_FOUND));
        // Isolated configuration fixtures roll back. No second Company seed is deployed.
        assertThatThrownBy(()->tx(()->{jdbc.update("delete from organization_company");return queries.findRole(company,UUID.randomUUID());})).isInstanceOfSatisfying(OrganizationFailure.class,f->assertThat(f.code()).isEqualTo(OrganizationFailure.Code.COMPANY_CONFIGURATION_INVALID));
        assertThat(organization.getCompany().companyId()).isEqualTo(company);
        doThrow(new OrganizationFailure(OrganizationFailure.Code.COMPANY_CONFIGURATION_INVALID)).when(organization).getCompany();
        assertThatThrownBy(()->service.createRole(create("R"))).isInstanceOfSatisfying(OrganizationFailure.class,f->assertThat(f.code()).isEqualTo(OrganizationFailure.Code.COMPANY_CONFIGURATION_INVALID));
        assertThat(count()).isZero(); // Multiple Company is prevented by existing singleton DB constraint; upstream configuration failure propagates.

    }
    @Test void t23_t24_scalarQueriesPaginationAndDbFailure(){
        var z=service.createRole(create("ZZZ"));var a=service.createRole(create("AAA"));var b=service.createRole(create("BBB"));
        assertThat(queries.findRole(company,UUID.randomUUID())).isEmpty();assertThat(queries.findRole(company,company)).isEmpty();assertThat(queries.findRoleByCode(company,"missing")).isEmpty();assertThat(queries.findRoleByCode(company," zzz ")).contains(z);
        assertThat(queries.listRoles(company,null,0,null).items()).extracting(RoleListItem::code).containsExactly("AAA","BBB","ZZZ");assertThat(queries.listRoles(company,null,0,1).hasNext()).isTrue();assertThat(queries.listRoles(company,null,2,1).hasNext()).isFalse();assertThat(queries.listRoles(company,null,3,1).items()).isEmpty();assertThat(queries.listRoles(company,null,0,100).items()).hasSize(3);
        for(int size:new int[]{0,101})failure(INVALID_INPUT,()->queries.listRoles(company,null,0,size));failure(INVALID_INPUT,()->queries.listRoles(company,null,-1,20));failure(INVALID_INPUT,()->queries.listRoles(company,null,Integer.MAX_VALUE,100));failure(INVALID_INPUT,()->queries.listRoles(company,"ENABLED",0,20));failure(INVALID_INPUT,()->queries.findRoleByCode(company,"bad.code"));
        tx(()->{var stale=em.find(Role.class,z.roleId());assertThat(stale.facts().version()).isZero();assertThat(result(job("writer",()->action("activate",z,0)))).isInstanceOf(RoleView.class);
            var id=physical();var result=queries.findRole(company,z.roleId()).orElseThrow();assertThat(result.version()).isEqualTo(1);assertThat(result.status()).isEqualTo("ACTIVE");assertThat(stale.facts().version()).isZero();assertThat(em.contains(stale)).isTrue();assertThat(queries.findRoleByCode(company,"zzz")).contains(result);
            var observed=sql(id);assertThat(observed.lines().filter(l->l.contains("select id,company_id,code,name,status,version,creation_reason,last_change_reason,last_change_kind,created_at,updated_at from iam_role")).count()).isEqualTo(2);
            System.out.println("I2 T23 single scalar SELECT per detail/code query despite stale managed Role retained\n"+observed);return null;});
        assertThat(queries.listRoles(company,"ACTIVE",0,20).items()).extracting(RoleListItem::roleId).containsExactly(z.roleId());assertThat(queries.listRoles(company,"INACTIVE",0,20).items()).extracting(RoleListItem::roleId).containsExactly(a.roleId(),b.roleId());
        doAnswer(c->{jdbc.execute("select 1/0");return Optional.empty();}).when(store).findRole(any(),any());assertThatThrownBy(()->queries.findRole(company,a.roleId())).isInstanceOf(RuntimeException.class);
        doAnswer(c->{jdbc.execute("select 1/0");return Optional.empty();}).when(store).findByCode(any(),any());assertThatThrownBy(()->queries.findRoleByCode(company,"AAA")).isInstanceOf(RuntimeException.class);
        doAnswer(c->{jdbc.execute("select 1/0");return List.of();}).when(store).list(any(),any(),anyInt(),anyInt());assertThatThrownBy(()->queries.listRoles(company,null,0,20)).isInstanceOf(RuntimeException.class);
    }
    @Test void t25_realExistingOrgHrUserBaselineIsNotChanged(){
        var site=organization.registerSite("S","site").siteId();var dep=organization.registerDepartment("D","department").departmentId();var pos=organization.registerPosition("P","position").positionId();
        // Use actual HR registration on Oct 1, then actual User creation with an independent Oct 11 clock.
        var hrStore=context.getBean(HrStore.class);
        var registration=new EmployeeRegistrationService(hrStore,context.getBean(OrganizationAssignmentGuard.class),manager,Clock.fixed(Instant.parse("2026-10-01T03:00:00Z"),ZoneOffset.UTC));
        var e=registration.createEmployee(new CreateEmployee(company,"I2-E","synthetic employee",LocalDate.of(2026,10,1),site,dep,pos,"baseline"));
        var user=context.getBean(UserService.class).createUser(new CreateUser(company,e.employee().id(),"i2.fixture","baseline"));
        assertThat(queries.findRole(company,user.userId())).isEmpty();
        var baseline=new LinkedHashMap<String,List<Map<String,Object>>>();for(String table:List.of("organization_company","organization_site","organization_department","organization_position","hr_employee","hr_employee_assignment","iam_user"))baseline.put(table,jdbc.queryForList("select * from "+table+" order by id"));
        var other=service.createRole(create("OTHER"));var otherRow=row(other.roleId());var ids=new ArrayList<Map<String,Object>>();doAnswer(c->{c.callRealMethod();ids.add(physical());return null;}).when(store).requireWritableTransaction();
        var r=service.createRole(create("ADMIN"));r=action("rename",r,0);r=action("activate",r,1);r=action("deactivate",r,2);r=action("activate",r,3);
        baseline.forEach((table,rows)->assertThat(jdbc.queryForList("select * from "+table+" order by id")).isEqualTo(rows));assertThat(row(other.roleId())).isEqualTo(otherRow);
        for(var id:ids)assertThat(sql(id)).doesNotContain("hr_employee","from iam_user where","update iam_user set","update organization_","FOR SHARE");
        assertThat(jdbc.queryForObject("select count(*) from iam_user_role",Integer.class)).isZero();System.out.println("I2 T25 actual committed HR/User baseline preserved including AM01/AM02 generations; other Role unchanged; IAM-03 table present but Role lifecycle creates no UserRole grant; Role ADMIN is ordinary stored definition; User="+user.userId());
    }
    @Autowired org.springframework.context.ApplicationContext context;
    @Test void t26_lockTimeoutIsTechnicalAndFinite(){
        var r=service.createRole(create("R"));var before=row(r.roleId());var held=gate();var release=gate();var id=new AtomicReference<Map<String,Object>>();
        var locker=job("locker",()->tx(()->{id.set(physical());jdbc.queryForObject("select id from iam_role where id=? FOR UPDATE",UUID.class,r.roleId());held.countDown();await(release);return true;}));await(held);
        doAnswer(c->{c.callRealMethod();assertThat(jdbc.queryForObject("show lock_timeout",String.class)).isEqualTo("5s");assertThat(jdbc.queryForObject("show statement_timeout",String.class)).isEqualTo("30s");jdbc.execute("set local lock_timeout='800ms'");return null;}).when(store).boundRootWaits(anyInt());
        var worker=job("worker",()->action("activate",r,0));blocked("iam_role",id.get());var f=result(worker);assertThat(f).isInstanceOf(RuntimeException.class).isNotInstanceOf(IamFailure.class);assertThat(pg((Throwable)f).getSQLState()).isEqualTo("55P03");release.countDown();assertThat(result(locker)).isEqualTo(true);assertThat(row(r.roleId())).isEqualTo(before);System.out.println("I2 T26 actual lock timeout55P03; default5s/30s bounded; no conflict/empty translation");
    }
    @Test void t26_deadlockRemainsTechnical(){
        var a=service.createRole(create("A"));var b=service.createRole(create("B"));var held=gate();var reverseHeld=gate();var release=gate();
        doAnswer(c->{c.callRealMethod();jdbc.execute("set local deadlock_timeout='100ms'");held.countDown();await(release);jdbc.queryForObject("select id from iam_role where id=? FOR UPDATE",UUID.class,b.roleId());return null;}).when(store).flush();
        var actual=job("actual",()->action("activate",a,0));await(held);var reverse=job("reverse",()->tx(()->{jdbc.execute("set local deadlock_timeout='100ms'");jdbc.queryForObject("select id from iam_role where id=? FOR UPDATE",UUID.class,b.roleId());reverseHeld.countDown();jdbc.queryForObject("select id from iam_role where id=? FOR UPDATE",UUID.class,a.roleId());return true;}));await(reverseHeld);blocked("iam_role",null);release.countDown();var r1=result(actual);var r2=result(reverse);
        var failures=List.of(r1,r2).stream().filter(RuntimeException.class::isInstance).map(RuntimeException.class::cast).toList();assertThat(failures).hasSize(1);assertThat(pg(failures.getFirst()).getSQLState()).isEqualTo("40P01");assertThat(store.translate(failures.getFirst())).isSameAs(failures.getFirst());assertThat(row(a.roleId()).get("version")).isEqualTo(r1 instanceof RuntimeException?0L:1L);assertThat(row(b.roleId()).get("version")).isEqualTo(0L);
        System.out.println("I2 T26 actual40P01 with test-only reverse locker; confirmed victim rollback and one survivor, no auto retry");
    }
    @Test void t26_connectionLossAfterFlushIsNotBusinessConflict(){
        doAnswer(c->{c.callRealMethod();int pid=(int)physical().get("pid");assertThat(result(job("killer",()->jdbc.queryForObject("select pg_terminate_backend(?)",Boolean.class,pid)))).isEqualTo(true);return null;}).when(store).flush();
        assertThatThrownBy(()->service.createRole(create("R"))).isInstanceOf(RuntimeException.class).isNotInstanceOf(IamFailure.class);assertThat(count()).isZero();System.out.println("I2 T26 server terminated connection after INSERT/flush before commit; fresh connection confirms abort. Not evidence of every commit-window network fault");
    }
    @ParameterizedTest @ValueSource(strings={"x","bmp500","emoji500"})
    void t07_validReasonBoundariesAcrossEveryCommandAndNoop(String example){
        String reason=example.equals("bmp500")?"x".repeat(500):example.equals("emoji500")?"😀".repeat(500):"x";
        var r=service.createRole(new CreateRole(company,"R","name","  "+reason+"  "));
        assertThat(r.creationReason()).isEqualTo(reason);
        r=service.renameRole(new RenameRole(company,r.roleId(),"renamed",0L,reason));assertThat(r.lastChangeReason()).isEqualTo(reason);
        r=service.activateRole(new ChangeRoleStatus(company,r.roleId(),1L,reason));assertThat(r.lastChangeReason()).isEqualTo(reason);
        var saved=row(r.roleId());var original=r;
        assertThat(service.activateRole(new ChangeRoleStatus(company,r.roleId(),2L,reason))).isEqualTo(original);assertThat(row(r.roleId())).isEqualTo(saved);
        r=service.deactivateRole(new ChangeRoleStatus(company,r.roleId(),2L,reason));assertThat(r.lastChangeReason()).isEqualTo(reason);assertThat(r.version()).isEqualTo(3);
    }

}
