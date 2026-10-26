package com.kflow.erp.organization;

import com.kflow.erp.KFlowErpApplication;
import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.application.*;
import com.kflow.erp.organization.domain.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import com.kflow.erp.organization.infrastructure.JpaOrganizationLockStore;
import org.hibernate.Session;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.kflow.testprobe.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.kflow.erp.organization.api.OrganizationFailure.Code.*;

@Testcontainers
@Timeout(60)
@SpringBootTest(classes=KFlowErpApplication.class)
@Import(OrganizationStateGuardIT.ProbeConfiguration.class)
class OrganizationStateGuardIT {
    @Container @ServiceConnection static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:16")
            .withInitScript("org03-probe.sql")
            .withCommand("postgres", "-c", "log_statement=all", "-c", "log_line_prefix=%p %x ");
    @TestConfiguration @EntityScan(basePackageClasses={KFlowErpApplication.class,ManagedProbe.class})
    static class ProbeConfiguration {
        @Bean TestAssignmentConsumer consumer(OrganizationAssignmentGuard guard, EntityManager em,
                JdbcTemplate jdbc, PlatformTransactionManager manager) {
            return new TestAssignmentConsumer(guard,em,jdbc,manager);
        }
    }
    @Autowired OrganizationService service;
    @Autowired OrganizationStateService states;
    @Autowired OrganizationAssignmentGuard guard;
    @Autowired TestAssignmentConsumer consumer;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory factory;
    @MockitoSpyBean OrganizationLockStore locks;
    @MockitoSpyBean OrganizationStore store;
    UUID company,site,department,position,probe;
    ExecutorService pool;
    final List<CountDownLatch> releaseOnCleanup=new ArrayList<>();
    record Row(UUID id,String name,String status,long version) {}
    String table(OrganizationTarget k) { return "organization_"+k.name().toLowerCase(Locale.ROOT); }
    UUID id(OrganizationTarget k) { return switch(k) {case SITE->site;case DEPARTMENT->department;case POSITION->position;}; }
    Map<String,Object> row(OrganizationTarget k) { return jdbc.queryForMap("select * from "+table(k)+" where id=?",id(k)); }
    OrganizationAssignmentTargets targets() { return new OrganizationAssignmentTargets(company,site,department,position); }
    TransactionTemplate tx() { return new TransactionTemplate(manager); }
    <T> T transaction(Supplier<T> action) { return tx().execute(s->action.get()); }
    void failure(OrganizationFailure.Code code,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(OrganizationFailure.class,e->assertThat(e.code()).isEqualTo(code));
    }
    OrganizationStateResult change(OrganizationTarget k,boolean active,Long version,String reason) {
        return change(k,company,id(k),active,version,reason);
    }
    OrganizationStateResult change(OrganizationTarget k,UUID c,UUID id,boolean active,Long version,String reason) {
        return switch(k) {
            case SITE -> active?states.activateSite(c,id,version,reason):states.deactivateSite(c,id,version,reason);
            case DEPARTMENT -> active?states.activateDepartment(c,id,version,reason):states.deactivateDepartment(c,id,version,reason);
            case POSITION -> active?states.activatePosition(c,id,version,reason):states.deactivatePosition(c,id,version,reason);
        };
    }
    Object rename(OrganizationTarget k,long version) {
        return switch(k) {
            case SITE -> service.renameSite(company,site,"renamed",version);
            case DEPARTMENT -> service.renameDepartment(company,department,"renamed",version);
            case POSITION -> service.renamePosition(company,position,"renamed",version);
        };
    }
    CountDownLatch gate() { var gate=new CountDownLatch(1);releaseOnCleanup.add(gate);return gate; }
    void await(CountDownLatch gate) {
        try { assertThat(gate.await(12,TimeUnit.SECONDS)).as("bounded test gate").isTrue(); }
        catch(InterruptedException e) {Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    Future<Object> job(Supplier<?> action) {
        return pool.submit(()->{try{return action.get();}catch(RuntimeException failure){return failure;}});
    }
    Object result(Future<Object> job) {
        try{return job.get(15,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}
    }
    void blocked(String query,int count) {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end) {
            int n=jdbc.queryForObject("select count(*) from pg_stat_activity where datname=current_database() "
                    +"and wait_event_type='Lock' and cardinality(pg_blocking_pids(pid))>0 and query like ?",Integer.class,"%"+query+"%");
            if(n>=count) {System.out.println("O3-EVIDENCE PostgreSQL blocked: "+query+" waiters="+n);return;}
            try{Thread.sleep(15);}catch(InterruptedException e){throw new AssertionError(e);}
        }
        fail("Expected observable PostgreSQL lock wait for "+query);
    }
    long refs() {return jdbc.queryForObject("select count(*) from org03_test_reference",Long.class);}
    String label() {return jdbc.queryForObject("select label from org03_probe.org03_test_probe where id=?",String.class,probe);}
    List<Map<String,Object>> consume(String label,Runnable after,boolean fail) {
        return consumer.consume(targets(),probe,label,()->{},after,fail);
    }
    void saveReference() {jdbc.update("insert into org03_test_reference values (?,?,?,?)",UUID.randomUUID(),site,department,position);}
    @BeforeEach void fixture() {
        reset(locks,store);
        pool=Executors.newFixedThreadPool(4);
        jdbc.execute("create table if not exists org03_test_reference (id uuid primary key, site_id uuid references organization_site(id), "
                +"department_id uuid references organization_department(id), position_id uuid references organization_position(id))");
        jdbc.update("delete from org03_test_reference");jdbc.update("delete from org03_probe.org03_test_probe");
        for(var k:OrganizationTarget.values()) jdbc.update("delete from "+table(k));
        company=service.getCompany().companyId();
        site=service.registerSite("S","site").siteId();
        department=service.registerDepartment("D","department").departmentId();
        position=service.registerPosition("P","position").positionId();
        probe=UUID.randomUUID();jdbc.update("insert into org03_probe.org03_test_probe values (?, 'original')",probe);
    }
    @AfterEach void cleanup() throws Exception {
        releaseOnCleanup.forEach(CountDownLatch::countDown);releaseOnCleanup.clear();
        pool.shutdown();assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();reset(locks,store);
    }

    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t01_t03_t08_t23_stateIdentityRetryAndReactivationPreserveExistingReferences(OrganizationTarget k) {
        consume("first",()->{},false);
        var before=row(k);var off=change(k,false,0L,"  closed  ");var saved=row(k);
        assertThat(off.changed()).isTrue();assertThat(off.version()).isEqualTo(1);assertThat(off.status()).isEqualTo("INACTIVE");
        assertThat(off.lastStateChangeReason()).isEqualTo("closed");assertThat(off.updatedAt()).isEqualTo(off.stateChangedAt());
        for(String field:List.of("id","company_id","code","name","created_at")) assertThat(saved.get(field)).isEqualTo(before.get(field));
        failure(VERSION_CONFLICT,()->change(k,false,0L,"retry"));
        assertThat(change(k,false,1L,"other reason").changed()).isFalse();assertThat(row(k)).isEqualTo(saved);
        rename(k,1L);var renamed=row(k);assertThat(renamed.get("status")).isEqualTo("INACTIVE");
        assertThat(renamed.get("state_changed_at")).isEqualTo(saved.get("state_changed_at"));
        assertThat(renamed.get("last_state_change_reason")).isEqualTo("closed");
        failure(k.inactive,()->consume("blocked",()->{},false));assertThat(refs()).isEqualTo(1);
        assertThat(change(k,true,2L,"reopen").version()).isEqualTo(3);
        failure(VERSION_CONFLICT,()->change(k,true,2L,"lost response retry"));
        var active=row(k);assertThat(change(k,true,3L,"different").changed()).isFalse();assertThat(row(k)).isEqualTo(active);
        consume("A",()->{},false);consume("B",()->{},false);change(k,false,3L,"closed again");
        assertThat(refs()).isEqualTo(3);assertThat(label()).isEqualTo("B");
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t09_t24_allRequestsValidateVersionAndReasonIncludingNoops(OrganizationTarget k) {
        for(boolean current:List.of(true,false)) {
            jdbc.update("update "+table(k)+" set status=? where id=?",current?"ACTIVE":"INACTIVE",id(k));
            for(boolean target:List.of(true,false)) {
                var before=row(k);
                for(Long invalid:Arrays.asList(null,-1L)) failure(INVALID_VERSION,()->change(k,target,invalid,"valid"));
                failure(VERSION_CONFLICT,()->change(k,target,42L,"valid"));
                for(String reason:Arrays.asList(null,"","   ","a\t","a\n","a\r","a\u0085","\uD800","😀".repeat(501)))
                    failure(INVALID_STATE_CHANGE_REASON,()->change(k,target,0L,reason));
                assertThat(row(k)).isEqualTo(before);
            }
        }
        assertThat(change(k,false,0L,"valid noop").stateChangedAt()).isNull();
        var changed=change(k,true,0L,"  "+"😀".repeat(500)+"  ");
        assertThat(changed.lastStateChangeReason().codePointCount(0,changed.lastStateChangeReason().length())).isEqualTo(500);
        var before=row(k);assertThat(change(k,true,1L,"x").changed()).isFalse();assertThat(row(k)).isEqualTo(before);
        assertThat(change(k,false,1L,"x").lastStateChangeReason()).isEqualTo("x");
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t02_caughtGuardFailureStillRollsBackWholeConsumer(OrganizationTarget k) {
        change(k,false,0L,"inactive");
        assertThatThrownBy(()->transaction(()->{
            jdbc.update("update org03_probe.org03_test_probe set label='before' where id=?",probe);
            failure(k.inactive,()->guard.requireAssignable(targets()));
            saveReference();return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(label()).isEqualTo("original");assertThat(refs()).isZero();
        assertThat(change(k,true,1L,"reopen").changed()).isTrue(); // prior locks released
    }
    @Test void t14_t17_managedProbeAndPhysicalTransactionSurviveGuardCommitAndRollback() {
        List<Map<String,Object>> inside=new CopyOnWriteArrayList<>();
        doAnswer(i->{Object r=i.callRealMethod();inside.add(em.unwrap(Session.class).doReturningWork(c->{
            try(var s=c.createStatement();var rows=s.executeQuery("select pg_backend_pid(),pg_current_xact_id()::text")) {
                rows.next();return Map.<String,Object>of("pid",rows.getInt(1),"xid",rows.getString(2));
            }
        }));return r;}).when(locks).lockForAssignment(any(),any(),any());
        var observed=consume("committed",()->{},false);
        assertThat(observed).hasSize(3).allMatch(observed.getFirst()::equals);
        assertThat(inside).hasSize(3).allMatch(observed.getFirst()::equals);
        assertThat(label()).isEqualTo("committed");assertThat(refs()).isEqualTo(1);
        assertThatThrownBy(()->consume("rolled back",()->{},true)).isInstanceOf(IllegalStateException.class);
        assertThat(label()).isEqualTo("committed");assertThat(refs()).isEqualTo(1);
        System.out.println("O3-EVIDENCE root / guard / write same physical PID+XID: "+observed+" guard="+inside.subList(0,3));
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t05_t14_guardFirstBlocksDeactivationUntilConsumerCompletion(OrganizationTarget k) {
        for(boolean rollback:List.of(false,true)) {
            if(!"ACTIVE".equals(row(k).get("status"))) change(k,true,((Number)row(k).get("version")).longValue(),"reset");
            long version=((Number)row(k).get("version")).longValue();long count=refs();
            var held=gate();var release=gate();
            var a=job(()->consume("consumer",()->{held.countDown();await(release);},rollback));await(held);
            var b=job(()->change(k,false,version,"close"));blocked("select status from "+table(k),1);assertThat(b.isDone()).isFalse();
            release.countDown();var first=result(a);var second=result(b);
            if(rollback) assertThat(first).isInstanceOf(IllegalStateException.class);else assertThat(first).isInstanceOf(List.class);
            assertThat(second).isInstanceOf(OrganizationStateResult.class);assertThat(refs()).isEqualTo(count+(rollback?0:1));
        }
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t06_t15_stateFirstMakesGuardReadCommittedStateAfterWait(OrganizationTarget k) {
        for(boolean rollback:List.of(true,false)) {
            var held=gate();var release=gate();AtomicBoolean once=new AtomicBoolean();
            doAnswer(i->{i.callRealMethod();if(once.compareAndSet(false,true)) {held.countDown();await(release);if(rollback) throw new IllegalStateException("abort root");}return null;})
                    .when(store).flush();
            var b=job(()->change(k,false,0L,"close"));await(held);
            var a=job(()->consume("after wait",()->{},false));blocked("select status from "+table(k),1);
            release.countDown();var stateResult=result(b);var guardResult=result(a);reset(store);
            if(rollback) {
                assertThat(stateResult).isInstanceOfSatisfying(org.springframework.dao.InvalidDataAccessApiUsageException.class,
                        e->assertThat(e).hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage("abort root"));
                assertThat(guardResult).isInstanceOf(List.class);
                assertThat(row(k).get("version")).isEqualTo(0L);assertThat(row(k).get("state_changed_at")).isNull();
            } else {
                assertThat(stateResult).isInstanceOf(OrganizationStateResult.class);
                assertThat(guardResult).isInstanceOfSatisfying(OrganizationFailure.class,e->assertThat(e.code()).isEqualTo(k.inactive));
            }
        }
        assertThat(refs()).isEqualTo(1);
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t07_t10_doubleClickAndConcurrentNoops(OrganizationTarget k) {
        var start=gate();var a=job(()->{await(start);return change(k,false,0L,"a");});var b=job(()->{await(start);return change(k,false,0L,"b");});start.countDown();
        var results=List.of(result(a),result(b));
        assertThat(results.stream().filter(OrganizationStateResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(OrganizationFailure.class::isInstance).map(OrganizationFailure.class::cast).map(OrganizationFailure::code)).containsExactly(VERSION_CONFLICT);
        var before=row(k);var c=job(()->change(k,false,1L,"c"));var d=job(()->change(k,false,1L,"d"));
        for(var r:List.of(result(c),result(d))) assertThat(r).isInstanceOfSatisfying(OrganizationStateResult.class,v->assertThat(v.changed()).isFalse());
        assertThat(row(k)).isEqualTo(before);
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t11_noopLockLivesThroughActualRootCompletion(OrganizationTarget k) {
        var completing=gate();var release=gate();AtomicBoolean once=new AtomicBoolean();
        doAnswer(i->{i.callRealMethod();if(once.compareAndSet(false,true)) TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){completing.countDown();await(release);}
        });return null;}).when(store).flush();
        var noop=job(()->change(k,true,0L,"noop"));await(completing);
        var rename=job(()->rename(k,0L));blocked("update "+table(k),1);assertThat(noop.isDone()).isFalse();assertThat(rename.isDone()).isFalse();
        release.countDown();assertThat(result(noop)).isInstanceOfSatisfying(OrganizationStateResult.class,r->assertThat(r.changed()).isFalse());
        assertThat(result(rename)).isNotInstanceOf(Throwable.class);assertThat(row(k).get("version")).isEqualTo(1L);
        assertThat(row(k).get("state_changed_at")).isNull();failure(VERSION_CONFLICT,()->change(k,true,0L,"stale noop"));
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t12_renameAndStateRaceNeverLoseUpdates(OrganizationTarget k) {
        var loaded=gate();var release=gate();AtomicBoolean once=new AtomicBoolean();
        if(k==OrganizationTarget.SITE) doAnswer(i->{var r=i.callRealMethod();if(once.compareAndSet(false,true)){loaded.countDown();await(release);}return r;}).when(store).site(any(),any());
        if(k==OrganizationTarget.DEPARTMENT) doAnswer(i->{var r=i.callRealMethod();if(once.compareAndSet(false,true)){loaded.countDown();await(release);}return r;}).when(store).department(any(),any());
        if(k==OrganizationTarget.POSITION) doAnswer(i->{var r=i.callRealMethod();if(once.compareAndSet(false,true)){loaded.countDown();await(release);}return r;}).when(store).position(any(),any());
        var rename=job(()->rename(k,0L));await(loaded);var state=change(k,false,0L,"state wins");release.countDown();
        assertThat(result(rename)).isInstanceOfSatisfying(OrganizationFailure.class,e->assertThat(e.code()).isEqualTo(VERSION_CONFLICT));
        assertThat(row(k).get("name")).isEqualTo(k.name().toLowerCase(Locale.ROOT));assertThat(state.version()).isEqualTo(1);
        reset(store);change(k,true,1L,"reset");
        var renamed=gate();var finishRename=gate();var firstFlush=new AtomicBoolean();
        doAnswer(i->{i.callRealMethod();if(firstFlush.compareAndSet(false,true)){renamed.countDown();await(finishRename);}return null;}).when(store).flush();
        var winningRename=job(()->rename(k,2L));await(renamed);
        var staleState=job(()->change(k,false,2L,"rename won"));blocked("select status from "+table(k),1);
        finishRename.countDown();assertThat(result(winningRename)).isNotInstanceOf(Throwable.class);
        assertThat(result(staleState)).isInstanceOfSatisfying(OrganizationFailure.class,e->assertThat(e.code()).isEqualTo(VERSION_CONFLICT));
        assertThat(row(k).get("status")).isEqualTo("ACTIVE");assertThat(row(k).get("name")).isEqualTo("renamed");
    }
    @Test void t02_partialGuardWaitKeepsEarlierLocksAndFailureAbortsRoot() {
        var deptHeld=gate();var deptRelease=gate();var once=new AtomicBoolean();
        doAnswer(i->{i.callRealMethod();if(once.compareAndSet(false,true)){deptHeld.countDown();await(deptRelease);}return null;}).when(store).flush();
        var b=job(()->change(OrganizationTarget.DEPARTMENT,false,0L,"close department"));await(deptHeld);
        var a=job(()->consume("must not save",()->{},false));blocked("select status from organization_department",1);
        var c=job(()->change(OrganizationTarget.SITE,false,0L,"close site"));blocked("select status from organization_site",1);
        deptRelease.countDown();assertThat(result(b)).isInstanceOf(OrganizationStateResult.class);
        assertThat(result(a)).isInstanceOfSatisfying(OrganizationFailure.class,e->assertThat(e.code()).isEqualTo(DEPARTMENT_INACTIVE));
        assertThat(result(c)).isInstanceOf(OrganizationStateResult.class);assertThat(refs()).isZero();assertThat(label()).isEqualTo("original");
    }
    @Test void t19_t20_shareGuardsCoexistBlockUnprefixedStatusUpdateAndEmitOrderedSql() {
        var one=gate();var two=gate();var releaseOne=gate();var releaseTwo=gate();
        var a=job(()->transaction(()->{guard.requireAssignable(targets());one.countDown();await(releaseOne);return "first";}));await(one);
        var b=job(()->transaction(()->{guard.requireAssignable(targets());two.countDown();await(releaseTwo);return "second";}));await(two);
        var update=job(()->transaction(()->{jdbc.update("update organization_site set status='INACTIVE' where id=?",site);return "updated";}));
        blocked("update organization_site set status",1);releaseOne.countDown();assertThat(result(a)).isEqualTo("first");
        blocked("update organization_site set status",1);assertThat(update.isDone()).isFalse();releaseTwo.countDown();
        assertThat(result(b)).isEqualTo("second");assertThat(result(update)).isEqualTo("updated");
        var order=new ArrayList<OrganizationTarget>();var signature=new AtomicReference<Map<String,Object>>();
        doAnswer(i->{order.add(i.getArgument(0));signature.set(jdbc.queryForMap("select pg_backend_pid() pid, pg_current_xact_id()::text xid"));return i.callRealMethod();})
                .when(locks).lockForAssignment(any(),any(),any());
        jdbc.update("update organization_site set status='ACTIVE' where id=?",site);
        transaction(()->{guard.requireAssignable(targets());return null;});assertThat(order).containsExactly(OrganizationTarget.SITE,OrganizationTarget.DEPARTMENT,OrganizationTarget.POSITION);
        String logs=postgres.getLogs();
        String signaturePrefix=signature.get().get("pid")+" "+signature.get().get("xid")+" ";
        var emitted=logs.lines().filter(line->line.startsWith(signaturePrefix) && line.contains("FOR SHARE")).toList();
        assertThat(emitted).hasSize(3);
        for(int i=0;i<3;i++) assertThat(emitted.get(i)).contains("select status from "+table(OrganizationTarget.values()[i])+" where company_id = $1 and id = $2 FOR SHARE");
        change(OrganizationTarget.SITE,false,0L,"observe state SQL");
        assertThat(postgres.getLogs()).contains("select status from organization_site where company_id = $1 and id = $2 FOR UPDATE");
        emitted.forEach(line->System.out.println("O3-SQL "+line));
        System.out.println("O3-EVIDENCE emitted SQL: FOR SHARE Site -> Department -> Position; concurrent guards coexisted; raw non-key UPDATE blocked. "+signature.get());
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t20_freshGuardIgnoresPreloadedStaleEntityWithoutClearingConsumer(OrganizationTarget k) {
        var loaded=gate();var proceed=gate();
        var a=job(()->transaction(()->{
            var managed=em.find(ManagedProbe.class,probe);var mode=em.getFlushMode();
            Class<?> entityType=switch(k){case SITE->Site.class;case DEPARTMENT->Department.class;case POSITION->Position.class;};
            Object old=em.find(entityType,id(k));
            loaded.countDown();await(proceed);
            failure(k.inactive,()->guard.requireAssignable(targets()));
            assertThat(em.contains(old)).isTrue();assertThat(em.contains(managed)).isTrue();assertThat(em.getFlushMode()).isEqualTo(mode);
            managed.label("should rollback");return null;
        }));await(loaded);change(k,false,0L,"concurrent");proceed.countDown();
        assertThat(result(a)).isInstanceOf(UnexpectedRollbackException.class);assertThat(label()).isEqualTo("original");
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t13_flushSuccessIsNotCommitSuccessAndDeferredAbortRestoresAllFields(OrganizationTarget k) {
        var before=row(k);
        doAnswer(i->{i.callRealMethod();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public void beforeCommit(boolean readOnly){throw new IllegalStateException("before commit abort");}
        });return null;}).when(store).flush();
        assertThatThrownBy(()->change(k,false,0L,"abort")).isInstanceOf(IllegalStateException.class);
        assertThat(row(k)).isEqualTo(before);reset(store);
        jdbc.execute("create function org03_reject_commit() returns trigger language plpgsql as $$ begin raise exception 'org03 deferred abort' using errcode='23514'; return null; end $$");
        jdbc.execute("create constraint trigger org03_deferred_abort after update on "+table(k)+" deferrable initially deferred for each row execute function org03_reject_commit()");
        try {
            var failure=catchThrowable(()->change(k,false,0L,"deferred abort"));
            assertThat(failure).isNotNull().isNotInstanceOf(OrganizationFailure.class);assertThat(sqlState(failure)).isEqualTo("23514");
            assertThat(row(k)).isEqualTo(before);
        } finally {jdbc.execute("drop trigger org03_deferred_abort on "+table(k));jdbc.execute("drop function org03_reject_commit()");}
    }
    @Test void t16_commitAcknowledgmentLossIsNotReportedAsRollbackOrRetried() {
        AtomicInteger commits=new AtomicInteger(),rollbacks=new AtomicInteger();
        PlatformTransactionManager lostAcknowledgment=new PlatformTransactionManager(){
            public TransactionStatus getTransaction(TransactionDefinition definition){return manager.getTransaction(definition);}
            public void commit(TransactionStatus status){manager.commit(status);commits.incrementAndGet();throw new TransactionSystemException("test lost commit acknowledgment");}
            public void rollback(TransactionStatus status){rollbacks.incrementAndGet();manager.rollback(status);}
        };
        var isolated=new OrganizationStateService(store,locks,lostAcknowledgment);
        assertThatThrownBy(()->isolated.deactivateSite(company,site,0L,"committed but reply lost")).isInstanceOf(TransactionSystemException.class);
        assertThat(commits.get()).isEqualTo(1);assertThat(rollbacks.get()).isZero();
        assertThat(row(OrganizationTarget.SITE).get("status")).isEqualTo("INACTIVE");
        failure(VERSION_CONFLICT,()->states.deactivateSite(company,site,0L,"retry"));
        assertThat(states.deactivateSite(company,site,1L,"fresh noop").changed()).isFalse();
        System.out.println("O3-EVIDENCE commit ACK loss injected after actual DB commit: persisted v1, no automatic retry or second rollback; re-read is not a receipt");
    }
    String sqlState(Throwable failure) {
        for(Throwable e=failure;e!=null;e=e.getCause()) if(e instanceof java.sql.SQLException sql) return sql.getSQLState();
        return null;
    }
    @Test void t16_lockTimeoutAndTerminatedConnectionRemainTechnicalFailures() {
        var held=gate();var release=gate();
        var holder=job(()->transaction(()->{jdbc.queryForObject("select id from organization_site where id=? for update",UUID.class,site);held.countDown();await(release);return null;}));await(held);
        var timeout=catchThrowable(()->transaction(()->{jdbc.execute("set local lock_timeout='150ms'");guard.requireAssignable(targets());saveReference();return null;}));
        assertThat(timeout).isNotInstanceOf(OrganizationFailure.class);assertThat(sqlState(timeout)).isEqualTo("55P03");
        assertThat(refs()).isZero();release.countDown();assertThat(result(holder)).isNull();
        var connected=gate();var killed=gate();AtomicInteger pid=new AtomicInteger();
        var victim=job(()->transaction(()->{
            pid.set(jdbc.queryForObject("select pg_backend_pid()",Integer.class));connected.countDown();await(killed);
            guard.requireAssignable(targets());saveReference();return null;
        }));await(connected);assertThat(jdbc.queryForObject("select pg_terminate_backend(?)",Boolean.class,pid.get())).isTrue();killed.countDown();
        assertThat(result(victim)).isInstanceOf(RuntimeException.class).isNotInstanceOf(OrganizationFailure.class);
        assertThat(refs()).isZero();assertThat(row(OrganizationTarget.SITE).get("status")).isEqualTo("ACTIVE");
    }
    @Test void t16_realPostgresDeadlockAbortsOneWholeRootWithoutBusinessTranslation() {
        var held=gate();var askForSite=gate();
        var b=job(()->transaction(()->{
            jdbc.execute("set local deadlock_timeout='100ms'");
            jdbc.queryForObject("select id from organization_department where id=? for update",UUID.class,department);
            jdbc.update("update org03_probe.org03_test_probe set label='other root' where id=?",probe);
            held.countDown();await(askForSite);
            jdbc.update("update organization_site set status='INACTIVE' where id=?",site);return "other root committed";
        }));await(held);
        var a=job(()->transaction(()->{jdbc.execute("set local deadlock_timeout='100ms'");guard.requireAssignable(targets());saveReference();return "guard committed";}));
        blocked("select status from organization_department",1);askForSite.countDown();
        var ar=result(a);var br=result(b);var failures=new ArrayList<Throwable>();
        if(ar instanceof Throwable e)failures.add(e);if(br instanceof Throwable e)failures.add(e);
        assertThat(failures).hasSize(1);assertThat(sqlState(failures.getFirst())).isEqualTo("40P01");
        assertThat(failures.getFirst()).isNotInstanceOf(OrganizationFailure.class);
        if(ar instanceof Throwable){assertThat(refs()).isZero();assertThat(label()).isEqualTo("other root");}
        else{assertThat(refs()).isEqualTo(1);assertThat(label()).isEqualTo("original");}
        System.out.println("O3-EVIDENCE real PG deadlock: SQLSTATE 40P01, one root aborted, no business-error translation");
    }
    @Test void t17_actualTransactionIsolationAndReadOnlyAreCheckedEvenWithNullMetadata() {
        failure(TRANSACTION_BOUNDARY_VIOLATION,()->guard.requireAssignable(targets()));
        transaction(()->{assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isNull();guard.requireAssignable(targets());return null;});
        for(String actual:List.of("repeatable read","serializable")) {
            for(Integer metadata:Arrays.asList(null,Connection.TRANSACTION_READ_COMMITTED)) {
                failure(TRANSACTION_BOUNDARY_VIOLATION,()->transaction(()->{
                    jdbc.execute("set transaction isolation level "+actual);
                    var original=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
                    TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(metadata);
                    try{guard.requireAssignable(targets());return null;}finally{TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(original);}
                }));
            }
        }
        failure(TRANSACTION_BOUNDARY_VIOLATION,()->transaction(()->{
            jdbc.execute("set transaction read only");guard.requireAssignable(targets());return null;
        }));
        var readOnly=tx();readOnly.setReadOnly(true);
        failure(TRANSACTION_BOUNDARY_VIOLATION,()->readOnly.execute(s->{guard.requireAssignable(targets());return null;}));
        failure(TRANSACTION_BOUNDARY_VIOLATION,()->transaction(()->{
            var original=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_SERIALIZABLE);
            try{guard.requireAssignable(targets());return null;}finally{TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(original);}
        }));
        assertThatThrownBy(()->transaction(()->{
            em.unwrap(Session.class).doWork(c->c.setAutoCommit(true)); // empty isolated fixture only
            try{failure(TRANSACTION_BOUNDARY_VIOLATION,()->guard.requireAssignable(targets()));}
            finally{em.unwrap(Session.class).doWork(c->c.setAutoCommit(false));}
            return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
    }
    @Test void t17_t22_stateCommandsRejectAmbientRootsIncludingAfterGuardAndAsyncGuardHasNoRoot() {
        transaction(()->{
            guard.requireAssignable(targets());
            for(var k:OrganizationTarget.values()) for(boolean active:List.of(true,false))
                failure(TRANSACTION_BOUNDARY_VIOLATION,()->change(k,active,0L,"not standalone"));
            return null;
        });
        var async=transaction(()->result(job(()->{guard.requireAssignable(targets());return null;})));
        assertThat(async).isInstanceOfSatisfying(OrganizationFailure.class,e->assertThat(e.code()).isEqualTo(TRANSACTION_BOUNDARY_VIOLATION));
        for(var k:OrganizationTarget.values()) assertThat(row(k).get("version")).isEqualTo(0L);
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t18_missingWrongKindAndCompanyRemainDistinct(OrganizationTarget k) {
        UUID wrong=switch(k){case SITE->department;case DEPARTMENT->position;case POSITION->site;};
        failure(k.missing,()->change(k,company,wrong,false,0L,"wrong kind"));
        failure(k.missing,()->change(k,company,UUID.randomUUID(),false,0L,"missing"));
        failure(COMPANY_NOT_FOUND,()->change(k,UUID.randomUUID(),id(k),false,0L,"wrong company"));
        var wrongTargets=new OrganizationAssignmentTargets(company,k==OrganizationTarget.SITE?wrong:site,
                k==OrganizationTarget.DEPARTMENT?wrong:department,k==OrganizationTarget.POSITION?wrong:position);
        failure(k.missing,()->transaction(()->{guard.requireAssignable(wrongTargets);return null;}));
        failure(COMPANY_NOT_FOUND,()->transaction(()->{guard.requireAssignable(new OrganizationAssignmentTargets(UUID.randomUUID(),site,department,position));return null;}));
        failure(INVALID_ASSIGNMENT_TARGETS,()->transaction(()->{guard.requireAssignable(null);return null;}));
        for(var t:List.of(new OrganizationAssignmentTargets(null,site,department,position),new OrganizationAssignmentTargets(company,null,department,position),
                new OrganizationAssignmentTargets(company,site,null,position),new OrganizationAssignmentTargets(company,site,department,null)))
            failure(INVALID_ASSIGNMENT_TARGETS,()->transaction(()->{guard.requireAssignable(t);return null;}));
    }
    @Test void t18_companyZeroAndMultipleAreConfigurationErrorsWithRollback() {
        failure(COMPANY_CONFIGURATION_INVALID,()->transaction(()->{
            jdbc.update("delete from org03_test_reference");for(var k:OrganizationTarget.values())jdbc.update("delete from "+table(k));
            jdbc.update("delete from organization_company");guard.requireAssignable(targets());return null;
        }));
        failure(COMPANY_CONFIGURATION_INVALID,()->transaction(()->{
            jdbc.execute("alter table organization_company drop constraint uq_organization_company_slot");
            jdbc.update("insert into organization_company select ?,singleton_key,code||'-2',name,version,created_at,updated_at from organization_company",UUID.randomUUID());
            guard.requireAssignable(targets());return null;
        }));
        assertThat(jdbc.queryForObject("select count(*) from organization_company",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname='uq_organization_company_slot'",Integer.class)).isEqualTo(1);
        assertThat(service.getCompany().companyId()).isEqualTo(company);
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t24_databaseRejectsHalfNullBlankAndOversizedStateEvidence(OrganizationTarget k) {
        String sql="update "+table(k)+" set state_changed_at=?,last_state_change_reason=? where id=?";
        var now=java.sql.Timestamp.from(java.time.Instant.now());
        for(Object[] pair:List.of(new Object[]{now,null},new Object[]{null,"reason"},new Object[]{now,""},new Object[]{now,"   "},new Object[]{now,"x".repeat(501)})) {
            var before=row(k);var failure=catchThrowable(()->transaction(()->{jdbc.update(sql,pair[0],pair[1],id(k));return null;}));
            assertThat(failure).isNotNull().isNotInstanceOf(OrganizationFailure.class);
            assertThat(sqlState(failure)).isIn("23514","22001");assertThat(row(k)).isEqualTo(before);
        }
        for(String valid:List.of("x","😀".repeat(500))) {
            jdbc.update(sql,now,valid,id(k));assertThat(row(k).get("last_state_change_reason")).isEqualTo(valid);
        }
        jdbc.update(sql,null,null,id(k));assertThat(row(k).get("state_changed_at")).isNull();
    }
    @Test void t17_unjoinedEntityManagerAndMissingBoundResourceCannotAuthorize() {
        try(var unjoined=factory.createEntityManager()) {
            var unjoinedGuard=new OrganizationAssignmentService(new JpaOrganizationLockStore(unjoined,factory,5000),manager);
            assertThatThrownBy(()->transaction(()->{
                failure(TRANSACTION_BOUNDARY_VIOLATION,()->unjoinedGuard.requireAssignable(targets()));
                saveReference();return null;
            })).isInstanceOf(UnexpectedRollbackException.class);
        }
        EntityManagerFactory unboundFactory=mock(EntityManagerFactory.class);
        var missingResourceGuard=new OrganizationAssignmentService(new JpaOrganizationLockStore(em,unboundFactory,5000),manager);
        assertThatThrownBy(()->transaction(()->{
            failure(TRANSACTION_BOUNDARY_VIOLATION,()->missingResourceGuard.requireAssignable(targets()));
            saveReference();return null;
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(refs()).isZero();
    }
    @Test void t20_guardDoesNotAutoFlushUnrelatedManagedChanges() {
        transaction(()->{
            var managed=em.find(ManagedProbe.class,probe);var mode=em.getFlushMode();
            managed.label("pending dirty value");
            guard.requireAssignable(targets());
            assertThat(em.contains(managed)).isTrue();assertThat(em.getFlushMode()).isEqualTo(mode);
            assertThat(label()).isEqualTo("original"); // actual JDBC read: no forced flush by Guard
            return null;
        });
        assertThat(label()).isEqualTo("pending dirty value");
    }
    @ParameterizedTest @EnumSource(OrganizationTarget.class)
    void t23_newTripleStillValidatesAnUnchangedInactiveDestination(OrganizationTarget k) {
        UUID otherSite=service.registerSite("S2","other site").siteId();
        UUID otherDepartment=service.registerDepartment("D2","other department").departmentId();
        UUID otherPosition=service.registerPosition("P2","other position").positionId();
        change(k,false,0L,"old destination inactive");
        var next=new OrganizationAssignmentTargets(company,k==OrganizationTarget.SITE?site:otherSite,
                k==OrganizationTarget.DEPARTMENT?department:otherDepartment,k==OrganizationTarget.POSITION?position:otherPosition);
        failure(k.inactive,()->transaction(()->{guard.requireAssignable(next);return null;}));
        assertThat(refs()).isZero();
        switch(k) {
            case SITE -> assertThat(service.findSite(company,site).orElseThrow().status()).isEqualTo("INACTIVE");
            case DEPARTMENT -> assertThat(service.findDepartment(company,department).orElseThrow().status()).isEqualTo("INACTIVE");
            case POSITION -> assertThat(service.findPosition(company,position).orElseThrow().status()).isEqualTo("INACTIVE");
        }
    }
    @Test void t21_databaseFkAloneDoesNotEnforceActiveAssignmentPolicy() {
        change(OrganizationTarget.SITE,false,0L,"inactive");saveReference();assertThat(refs()).isEqualTo(1);
        failure(SITE_INACTIVE,()->consume("blocked by guard",()->{},false));assertThat(refs()).isEqualTo(1);
        System.out.println("O3-LIMITATION FK permits direct reference to INACTIVE; provider guard blocks normal consumer. Real HR paths DEFERRED.");
    }
}
