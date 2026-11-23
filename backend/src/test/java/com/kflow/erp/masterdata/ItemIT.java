package com.kflow.erp.masterdata;

import com.kflow.erp.KFlowErpApplication;
import com.kflow.erp.masterdata.api.*;
import com.kflow.erp.masterdata.application.*;
import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.domain.Company;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.kflow.testprobe.ManagedProbe;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.kflow.erp.masterdata.api.ItemFailure.Code.*;

@Testcontainers
@SpringBootTest(classes=KFlowErpApplication.class)
@Import(ItemIT.ProbeConfiguration.class)
@Timeout(60)
class ItemIT {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16")
            .withInitScript("org03-probe.sql").withCommand("postgres", "-c", "log_statement=all", "-c", "log_line_prefix=%p %x ");
    @TestConfiguration @EntityScan(basePackageClasses={KFlowErpApplication.class,ManagedProbe.class}) static class ProbeConfiguration {}
    @Autowired ItemService service;
    @Autowired ItemBusinessUseGuard use;
    @Autowired ItemFulfillmentGuard fulfillment;
    @Autowired ItemReferenceGuard references;
    @Autowired UnitReferenceQueries units;
    @MockitoSpyBean ItemStore store;
    @Autowired OrganizationReferenceQueries organization;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired DataSource dataSource;
    @MockitoBean(name="itemClock") Clock clock;
    final Instant now = Instant.parse("2026-11-22T03:00:00Z");
    UUID company; ExecutorService pool; List<CountDownLatch> gates;
    @BeforeEach void setup() {
        pool = Executors.newFixedThreadPool(3); gates = new ArrayList<>();
        when(clock.instant()).thenReturn(now);
        jdbc.execute("drop table if exists org03_probe.item_reference_probe");
        jdbc.update("delete from masterdata_item"); jdbc.update("delete from org03_probe.org03_test_probe");
        company = organization.getCompany().companyId();
    }
    @AfterEach void cleanup() throws Exception {
        gates.forEach(CountDownLatch::countDown); pool.shutdownNow(); assertThat(pool.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }
    ItemCommands.Create create(String code) { return new ItemCommands.Create(code, "PCB😀", null, "RAW_MATERIAL", "PURCHASED_MATERIAL", Unit.EA, true, null); }
    ItemView item(String code) { return service.create(create(code)); }
    ObservedItem observed(ItemView item) { return new ObservedItem(item.itemId(), item.baseUom(), item.usageProfile(), item.stockManaged()); }
    ItemBusinessUseGuard.Source source() { return new ItemBusinessUseGuard.Source(ItemBusinessUseGuard.UseKind.PO_CONFIRMATION, UUID.randomUUID()); }
    ItemView attributes(ItemView item, String code, Unit unit, BigDecimal price) {
        return service.changeAttributes(new ItemCommands.Attributes(company, item.itemId(), item.version(), code, item.category(), item.usageProfile(), unit, item.stockManaged(), price));
    }
    ItemView state(ItemView item, String state) { return service.changeStatus(new ItemCommands.State(company, item.itemId(), item.version(), state)); }
    ItemView latest(ItemView item) { return service.find(company, item.itemId()).orElseThrow(); }
    Map<String,Object> row(ItemView item) { return jdbc.queryForMap("select * from masterdata_item where id=?", item.itemId()); }
    <T> T tx(Supplier<T> action) { return new TransactionTemplate(manager).execute(s -> action.get()); }
    void failure(ItemFailure.Code code, Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ItemFailure.class, f -> assertThat(f.code()).isEqualTo(code)); }
    PSQLException pg(Throwable failure) { for (Throwable c=failure; c!=null; c=c.getCause()) if (c instanceof PSQLException p) return p; throw new AssertionError("No PG cause", failure); }
    Map<String,Object> physical() {
        return em.unwrap(Session.class).doReturningWork(c -> { try (var s=c.createStatement(); var r=s.executeQuery("select pg_backend_pid(),pg_current_xact_id()::text")) {
            r.next(); return Map.of("pid",r.getInt(1),"xid",r.getString(2));
        }});
    }
    String sql(Map<String,Object> physical) { String key=physical.get("pid")+" "+physical.get("xid")+" "; return postgres.getLogs().lines().filter(l -> l.contains(key)).reduce("", (a,b)->a+b+"\n"); }
    CountDownLatch gate() { var g=new CountDownLatch(1); gates.add(g); return g; }
    void await(CountDownLatch g) { try { assertThat(g.await(12,TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); } }
    Future<Object> job(String name, Supplier<?> action) {
        return pool.submit(() -> { Thread.currentThread().setName(name); try { return action.get(); } catch (RuntimeException e) { return e; } });
    }
    Object result(Future<Object> future) { try { return future.get(18, TimeUnit.SECONDS); } catch (Exception e) { throw new AssertionError(e); } }
    void blocked(String query, Map<String,Object> holder) {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime()<until) {
            var rows=jdbc.queryForList("select pid,pg_blocking_pids(pid)::text blockers,query from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid)) and query ilike ?", holder.get("pid"), "%"+query+"%");
            if (!rows.isEmpty()) { assertThat(rows).allSatisfy(r -> assertThat(r.get("pid")).isNotEqualTo(holder.get("pid"))); System.out.println("ITEM actual blocker holder="+holder+" waiter="+rows); return; }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        fail("No actual PostgreSQL wait for "+query+" holder="+holder);
    }
    @Test void t01_t05_roundTripAndNewTransaction() {
        var i=item(" pcb-a "); assertThat(i.itemId().version()).isEqualTo(4); assertThat(i.code()).isEqualTo("PCB-A");
        assertThat(i.companyId()).isEqualTo(company); assertThat(i.status()).isEqualTo("ACTIVE"); assertThat(i.version()).isZero(); assertThat(i.firstUsedAt()).isNull();
        assertThat(latest(i)).isEqualTo(i); assertThat(service.findItem(company,i.itemId())).contains(new ItemReference(i.itemId(),company,i.code(),i.name(),Unit.EA,"PURCHASED_MATERIAL",true,"ACTIVE",0));
        when(clock.instant()).thenReturn(now.plusSeconds(1));
        var changed=service.describe(new ItemCommands.Describe(company,i.itemId(),0L,"PCB new","new specification"));
        assertThat(changed.version()).isEqualTo(1); assertThat(changed.createdAt()).isEqualTo(now); assertThat(changed.updatedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(latest(changed)).isEqualTo(changed); assertThat(changed.itemId()).isEqualTo(i.itemId());
        assertThat(service.findItem(UUID.randomUUID(),i.itemId())).isEmpty();
        assertThatThrownBy(() -> service.describe(new ItemCommands.Describe(UUID.randomUUID(),i.itemId(),1L,"wrong",null))).isInstanceOf(OrganizationFailure.class);
        assertThat(row(changed).get("name")).isEqualTo("PCB new");
        System.out.println("ITEM runtime PG="+jdbc.queryForObject("show server_version",String.class)+" Hibernate="+org.hibernate.Version.getVersionString()+" image="+postgres.getDockerImageName()+" roundTrip="+changed);
    }
    @Test void t04_codeReuseInactiveUniquenessAndStableId() {
        var original=item("old"); var inactive=state(original,"INACTIVE");
        failure(ITEM_CODE_CONFLICT,()->item(" OLD "));
        var renamed=attributes(inactive,"new",Unit.EA,null);
        assertThat(renamed.itemId()).isEqualTo(original.itemId()); assertThat(renamed.status()).isEqualTo("INACTIVE");
        assertThat(item("old").itemId()).isNotEqualTo(renamed.itemId());
        failure(ITEM_CODE_CONFLICT,()->attributes(renamed,"old",Unit.EA,null)); assertThat(latest(renamed)).isEqualTo(renamed);
    }
    @Test void t06_t13_t14_firstUseOnceAndUsedRestrictions() {
        var i=item("used"); var s=source();
        var p=tx(() -> { var a=physical(); use.lockForNewUse(company,List.of(observed(i),observed(i)),s); use.lockForNewUse(company,List.of(observed(i)),source()); assertThat(physical()).isEqualTo(a); return a; });
        var used=latest(i); assertThat(used.version()).isEqualTo(1); assertThat(used.firstUseSourceId()).isEqualTo(s.sourceId()); assertThat(used.firstUsedAt()).isEqualTo(now);
        failure(ITEM_ALREADY_USED,()->attributes(used,"other",Unit.EA,null));
        var described=service.describe(new ItemCommands.Describe(company,i.itemId(),1L,"visible rename",null));
        var priced=service.changePrice(new ItemCommands.Price(company,i.itemId(),2L,Unit.EA,BigDecimal.ZERO));
        var inactive=state(priced,"INACTIVE"); var active=state(inactive,"ACTIVE");
        assertThat(active.firstUseSourceId()).isEqualTo(s.sourceId()); failure(ITEM_ALREADY_USED,()->attributes(active,active.code(),Unit.KG,null));
        assertThat(described.firstUseSourceId()).isEqualTo(s.sourceId());
        assertThat(sql(p).lines().filter(l->l.contains("update masterdata_item set")).toList()).hasSize(1);
        System.out.println("ITEM first-use same physical root="+p+" firstSource="+s+"\n"+sql(p));
    }
    @Test void t07_t08_t09_uomPriceIsAtomicAndObservedUnitProtected() {
        var i=item("price"); var p=service.changePrice(new ItemCommands.Price(company,i.itemId(),0L,Unit.EA,new BigDecimal("12000")));
        var changed=attributes(p,p.code(),Unit.KG,new BigDecimal("500")); assertThat(changed.referencePrice()).isEqualTo("500"); assertThat(changed.version()).isEqualTo(2);
        failure(VERSION_CONFLICT,()->service.changePrice(new ItemCommands.Price(company,i.itemId(),1L,Unit.EA,BigDecimal.TEN)));
        failure(ITEM_FACTS_CHANGED,()->service.changePrice(new ItemCommands.Price(company,i.itemId(),2L,Unit.EA,BigDecimal.TEN)));
        var reset=attributes(changed,changed.code(),Unit.BOX,null); assertThat(reset.referencePrice()).isNull();
        var zero=service.changePrice(new ItemCommands.Price(company,i.itemId(),3L,Unit.BOX,BigDecimal.ZERO)); assertThat(zero.referencePrice()).isEqualTo("0");
        failure(INVALID_INPUT,()->attributes(zero,zero.code(),Unit.KG,new BigDecimal("0.1"))); assertThat(latest(zero)).isEqualTo(zero);
        var samePrice=attributes(zero,zero.code(),Unit.KG,BigDecimal.ZERO); assertThat(samePrice.referencePrice()).isEqualTo("0");
    }
    @Test void t10_unitCatalogAndPrecision() {
        assertThat(units.supportedUnits()).containsExactly(Unit.EA,Unit.SET,Unit.BOX,Unit.KG);
        units.requireQuantity(Unit.KG,new BigDecimal("0.001")); units.requireQuantity(Unit.EA,new BigDecimal("1.0000"));
        failure(INVALID_INPUT,()->units.requireQuantity(Unit.KG,new BigDecimal("0.0001")));
        for (var u:List.of(Unit.EA,Unit.SET,Unit.BOX)) failure(INVALID_INPUT,()->units.requireQuantity(u,new BigDecimal("1.5")));
    }
    @Test void t12_staleBeforeNoopAndNoopPreservesTime() {
        var i=item("stale"); var inactive=state(i,"INACTIVE"); var before=row(inactive);
        when(clock.instant()).thenReturn(now.plusSeconds(100));
        failure(VERSION_CONFLICT,()->state(i,"INACTIVE")); assertThat(state(inactive,"INACTIVE")).isEqualTo(inactive);
        assertThat(service.describe(new ItemCommands.Describe(company,i.itemId(),1L,i.name(),null))).isEqualTo(inactive);
        assertThat(row(inactive)).isEqualTo(before);
    }
    @Test void t13_consumerFailureAndCaughtGuardFailureRollbackRoot() {
        var i=item("rollback"); UUID probe=UUID.randomUUID();
        assertThatThrownBy(() -> tx(() -> { jdbc.update("insert into org03_probe.org03_test_probe values(?,'consumer')",probe); use.lockForNewUse(company,List.of(observed(i)),source()); throw new IllegalStateException("test-only downstream failure after Item SQL"); })).isInstanceOf(IllegalStateException.class);
        assertThat(latest(i)).isEqualTo(i); assertThat(jdbc.queryForObject("select count(*) from org03_probe.org03_test_probe",Integer.class)).isZero();
        assertThatThrownBy(() -> tx(() -> { jdbc.update("insert into org03_probe.org03_test_probe values(?,'consumer')",probe); failure(ITEM_FACTS_CHANGED,()->use.lockForNewUse(company,List.of(new ObservedItem(i.itemId(),Unit.KG,i.usageProfile(),true)),source())); return null; })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(latest(i)).isEqualTo(i); assertThat(jdbc.queryForObject("select count(*) from org03_probe.org03_test_probe",Integer.class)).isZero();
    }
    @Test void t14_partialBatchFailureRollsBackNewMarkerButPreservesEarlierUse() {
        var a=item("A"); var b=item("B"); tx(()->use.lockForNewUse(company,List.of(observed(a)),source())); var usedA=latest(a);
        var inactiveB=state(b,"INACTIVE");
        failure(ITEM_INACTIVE,()->tx(()->use.lockForNewUse(company,List.of(observed(a),observed(b)),source())));
        assertThat(latest(a)).isEqualTo(usedA); assertThat(latest(b)).isEqualTo(inactiveB);
        var c=item("C"); var d=item("D");
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("after first native update");}).when(store).update(any(),any());
        assertThatThrownBy(()->tx(()->use.lockForNewUse(company,List.of(observed(c),observed(d)),source()))).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalStateException.class).hasMessageContaining("after first native update");
        assertThat(latest(c)).isEqualTo(c); assertThat(latest(d)).isEqualTo(d);
    }
    @Test void t17_freshSqlAndUnrelatedManagedEntitiesAreNotFlushedClearedOrDetached() {
        var i=item("fresh"); UUID probeId=UUID.randomUUID(); jdbc.update("insert into org03_probe.org03_test_probe values(?,'old')",probeId);
        tx(()->{
            var companyEntity=em.find(Company.class,company); var probe=em.find(ManagedProbe.class,probeId); var initialCompanyName=companyEntity.name();
            companyEntity.rename("test-only pending company rename",companyEntity.version(),now); probe.label("pending");
            var old=service.findItem(company,i.itemId()).orElseThrow();
            var outside=result(job("outside-rename",()->service.describe(new ItemCommands.Describe(company,i.itemId(),0L,"committed outside",null)))); assertThat(outside).isInstanceOf(ItemView.class);
            var returned=use.lockForNewUse(company,List.of(observed(i)),source()).getFirst(); assertThat(returned.name()).isEqualTo("committed outside"); assertThat(old.name()).isEqualTo(i.name());
            assertThat(em.contains(probe)).isTrue(); assertThat(em.contains(companyEntity)).isTrue();
            assertThat(jdbc.queryForObject("select label from org03_probe.org03_test_probe where id=?",String.class,probeId)).isEqualTo("old");
            assertThat(jdbc.queryForObject("select name from organization_company where id=?",String.class,company)).isEqualTo(initialCompanyName);
            return null;
        });
        assertThat(jdbc.queryForObject("select label from org03_probe.org03_test_probe where id=?",String.class,probeId)).isEqualTo("pending");
    }
    @ParameterizedTest @ValueSource(strings={"none","readonly","rr","serializable","db-readonly","db-rr","wrong-manager"})
    void t18_wrongTransactionIsRejected(String kind) {
        var i=item("boundary"); Runnable action=()->references.protectForDraft(company,List.of(i.itemId()));
        if (kind.equals("none")) { failure(TRANSACTION_BOUNDARY_VIOLATION,action); return; }
        var template=new TransactionTemplate(kind.equals("wrong-manager")?new DataSourceTransactionManager(dataSource):manager);
        if (kind.equals("readonly")) template.setReadOnly(true);
        if (kind.equals("rr")) template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        if (kind.equals("serializable")) template.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        failure(TRANSACTION_BOUNDARY_VIOLATION,()->template.execute(s->{
            if(kind.equals("db-readonly"))jdbc.execute("set transaction read only");
            if(kind.equals("db-rr"))jdbc.execute("set transaction isolation level repeatable read");
            action.run();return null;
        })); assertThat(latest(i)).isEqualTo(i);
    }
    @Test void t18_nullSpringIsolationStillChecksDatabaseAndCommandsRejectAmbientRoot() {
        var i=item("rc"); tx(()->{assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()).isNull(); references.protectForDraft(company,List.of(i.itemId())); failure(TRANSACTION_BOUNDARY_VIOLATION,()->item("ambient")); return null;});
        assertThat(latest(i)).isEqualTo(i);
    }
    @Test void providerFulfillmentAllowsOnlyAlreadyUsedFactsWithoutMarkingUse() {
        var i=item("existing"); failure(ITEM_NOT_USED,()->tx(()->fulfillment.lockForExistingPoReceipt(company,List.of(observed(i)))));
        tx(()->use.lockForNewUse(company,List.of(observed(i)),source())); var inactive=state(latest(i),"INACTIVE"); var before=row(inactive);
        assertThat(tx(()->fulfillment.lockForExistingPoReceipt(company,List.of(observed(i))))).hasSize(1);
        failure(ITEM_INACTIVE,()->tx(()->use.lockForNewUse(company,List.of(observed(i)),source())));
        failure(ITEM_FACTS_CHANGED,()->tx(()->fulfillment.lockForExistingPoReceipt(company,List.of(new ObservedItem(i.itemId(),Unit.KG,i.usageProfile(),true)))));
        assertThat(row(inactive)).isEqualTo(before);
        System.out.println("ITEM provider-only existing-PO exception: inactive accepted, used/UOM/profile checked; actual PO/remaining/LOT not implemented or certified");
    }
    @Test void contradictoryDuplicateObservationsAndMissingIdsDoNotMarkAnyItem() {
        var i=item("duplicate");
        failure(ITEM_FACTS_CHANGED,()->tx(()->use.lockForNewUse(company,List.of(observed(i),new ObservedItem(i.itemId(),Unit.KG,i.usageProfile(),true)),source())));
        failure(ITEM_NOT_FOUND,()->tx(()->references.protectForDraft(company,List.of(i.itemId(),UUID.randomUUID()))));
        failure(ITEM_NOT_FOUND,()->tx(()->references.protectForDraft(UUID.randomUUID(),List.of(i.itemId()))));
        assertThat(latest(i)).isEqualTo(i);
    }
    @Test void t20_paginationCountAndRowsOneStatementIncludingEmptyPage() {
        for (int n=0;n<9;n++) item("P"+n);
        var one=service.list(company,null,"P",ItemPage.Sort.CODE,0,4); var two=service.list(company,null,"P",ItemPage.Sort.CODE,1,4);
        assertThat(one.total()).isEqualTo(9); assertThat(two.items()).extracting(ItemView::code).containsExactly("P4","P5","P6","P7");
        assertThat(one.items()).extracting(ItemView::itemId).doesNotContainAnyElementsOf(two.items().stream().map(ItemView::itemId).toList());
        var empty=service.list(company,null,"P",ItemPage.Sort.NAME,100,4); assertThat(empty.items()).isEmpty(); assertThat(empty.total()).isEqualTo(9);
        assertThat(service.list(company,null,"%",ItemPage.Sort.UPDATED_AT,0,20).total()).isZero();
        failure(INVALID_INPUT,()->service.list(company,null,null,ItemPage.Sort.CODE,0,101));
    }
    @Test void t24_profilesAndNoPurchaseForManufacturedFinished() {
        var i=service.create(new ItemCommands.Create("FG","finished",null,"FINISHED_GOOD","MANUFACTURED_FINISHED",Unit.SET,true,null));
        failure(ITEM_NOT_PURCHASABLE,()->tx(()->use.lockForNewUse(company,List.of(observed(i)),source())));
        tx(()->use.lockForNewUse(company,List.of(observed(i)),new ItemBusinessUseGuard.Source(ItemBusinessUseGuard.UseKind.STOCK_POSTING,UUID.randomUUID())));
        assertThat(latest(i).firstUseKind()).isEqualTo("STOCK_POSTING");
        failure(UNSUPPORTED_ITEM_PROFILE,()->service.create(new ItemCommands.Create("MAC","internal",null,"CONSUMABLE","INTERNAL_USE",Unit.EA,false,null)));
    }
    @Test void overflowRollsBackAllFactsAndNoopStillSucceeds() {
        var i=item("overflow"); jdbc.update("update masterdata_item set version=? where id=?",Long.MAX_VALUE,i.itemId()); var before=row(i); var current=latest(i);
        failure(VERSION_EXHAUSTED,()->state(current,"INACTIVE")); assertThat(row(i)).isEqualTo(before);
        assertThat(state(current,"ACTIVE")).isEqualTo(current);
        failure(VERSION_EXHAUSTED,()->tx(()->use.lockForNewUse(company,List.of(observed(i)),source()))); assertThat(row(i)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void t01_t02_uniqueInsertWaitsForCommitOrRollback(boolean commit) {
        var held=gate(); var release=gate(); var owner=new AtomicReference<Map<String,Object>>();
        doAnswer(call->{call.callRealMethod(); if(Thread.currentThread().getName().equals("unique-owner")) { owner.set(physical()); held.countDown(); await(release); if(!commit)throw new IllegalStateException("test rollback unique owner"); } return null;}).when(store).insert(any());
        var a=job("unique-owner",()->item(" Same ")); await(held); var b=job("unique-waiter",()->item("same")); blocked("insert into masterdata_item",owner.get()); release.countDown();
        var ar=result(a); var br=result(b);
        if(commit){assertThat(ar).isInstanceOf(ItemView.class);assertThat(br).isInstanceOfSatisfying(ItemFailure.class,f->assertThat(f.code()).isEqualTo(ITEM_CODE_CONFLICT));assertThat(pg((Throwable)br).getSQLState()).isEqualTo("23505");assertThat(pg((Throwable)br).getServerErrorMessage().getConstraint()).isEqualTo("uq_masterdata_item_company_code");}
        else{assertThat((Throwable)ar).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalStateException.class).hasMessageContaining("test rollback");assertThat(br).isInstanceOf(ItemView.class);}
        assertThat(jdbc.queryForObject("select count(*) from masterdata_item",Integer.class)).isEqualTo(1);
        System.out.println("ITEM UNIQUE owner commit="+commit+" results="+ar+" / "+br);
    }
    @ParameterizedTest @CsvSource({"use,true", "use,false", "attributes,true", "attributes,false", "deactivate,true", "deactivate,false", "use-state,true", "use-state,false"})
    void t15_t16_firstUseAgainstAttributeOrStateChange(String first, boolean commit) {
        var i=item("race"); var held=gate(); var release=gate(); var owner=new AtomicReference<Map<String,Object>>();
        doAnswer(call->{call.callRealMethod();if(Thread.currentThread().getName().equals("race-owner")){owner.set(physical());held.countDown();await(release);if(!commit)throw new IllegalStateException("test rollback race owner");}return null;}).when(store).update(any(),any());
        Supplier<?> firstAction=switch(first){case "use", "use-state"->()->tx(()->use.lockForNewUse(company,List.of(observed(i)),source()));case "attributes"->()->attributes(i,i.code(),Unit.KG,null);default->()->state(i,"INACTIVE");};
        Supplier<?> secondAction=first.equals("use")?()->attributes(i,i.code(),Unit.KG,null):first.equals("use-state")?()->state(i,"INACTIVE"):()->tx(()->use.lockForNewUse(company,List.of(observed(i)),source()));
        var a=job("race-owner",firstAction);await(held);var b=job("race-waiter",secondAction);blocked("FOR UPDATE",owner.get());release.countDown();var ar=result(a);var br=result(b);
        if(commit){assertThat(ar).isNotInstanceOf(Throwable.class);ItemFailure.Code code=switch(first){case "use", "use-state"->VERSION_CONFLICT;case "attributes"->ITEM_FACTS_CHANGED;default->ITEM_INACTIVE;};assertThat(br).isInstanceOfSatisfying(ItemFailure.class,f->assertThat(f.code()).isEqualTo(code));}
        else{assertThat((Throwable)ar).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalStateException.class).hasMessageContaining("test rollback");assertThat(br).isNotInstanceOf(Throwable.class);}
        assertThat(latest(i).version()).isEqualTo(1);
        if(first.equals("use")){assertThat(latest(i).firstUsedAt()!=null).isEqualTo(commit);assertThat(latest(i).baseUom()).isEqualTo(commit?Unit.EA:Unit.KG);}
        else if(first.equals("use-state")){assertThat(latest(i).firstUsedAt()!=null).isEqualTo(commit);assertThat(latest(i).status()).isEqualTo(commit?"ACTIVE":"INACTIVE");}
        else{assertThat(latest(i).firstUsedAt()!=null).isEqualTo(!commit);}
        System.out.println("ITEM race first="+first+" commit="+commit+" final="+latest(i));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void t11_t12_sameVersionMutationAndNoopSerialize(boolean noopFirst) {
        var i=item("edit-race");var held=gate();var release=gate();var owner=new AtomicReference<Map<String,Object>>();
        doAnswer(call->{var rows=call.callRealMethod();if(Thread.currentThread().getName().equals("edit-owner")){owner.set(physical());held.countDown();await(release);}return rows;}).when(store).lock(eq(company),anyCollection(),eq(ItemStore.Lock.UPDATE));
        var a=job("edit-owner",()->state(i,noopFirst?"ACTIVE":"INACTIVE"));await(held);var b=job("edit-waiter",()->state(i,noopFirst?"INACTIVE":"ACTIVE"));blocked("FOR UPDATE",owner.get());release.countDown();
        assertThat(result(a)).isInstanceOf(ItemView.class);var br=result(b);if(noopFirst)assertThat(br).isInstanceOf(ItemView.class);else assertThat(br).isInstanceOfSatisfying(ItemFailure.class,f->assertThat(f.code()).isEqualTo(VERSION_CONFLICT));
        assertThat(latest(i).version()).isEqualTo(1);assertThat(latest(i).status()).isEqualTo("INACTIVE");
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void referenceKeyShareAndOwnerUpdateConflictBothDirections(boolean referenceFirst) {
        var i=item("lock-mode"); var held=gate();var release=gate();var owner=new AtomicReference<Map<String,Object>>();
        doAnswer(call->{var rows=call.callRealMethod();if(Thread.currentThread().getName().equals("mode-owner")){owner.set(physical());held.countDown();await(release);}return rows;}).when(store).lock(eq(company),anyCollection(),any());
        var a=job("mode-owner",referenceFirst?()->tx(()->references.protectForDraft(company,List.of(i.itemId()))):()->state(i,"INACTIVE"));await(held);
        var b=job("mode-waiter",referenceFirst?()->state(i,"INACTIVE"):()->tx(()->references.protectForDraft(company,List.of(i.itemId()))));
        blocked(referenceFirst?"FOR UPDATE":"FOR KEY SHARE",owner.get());release.countDown();assertThat(result(a)).isNotInstanceOf(Throwable.class);assertThat(result(b)).isNotInstanceOf(Throwable.class);
        assertThat(latest(i).firstUsedAt()).isNull();assertThat(latest(i).status()).isEqualTo("INACTIVE");
        System.out.println("ITEM mode evidence referenceFirst="+referenceFirst+"\n"+sql(owner.get()));
    }
    @Test void keyShareCoexistsAndDoesNotPretendToBeEligibilityLock() {
        var i=item("compatible");var held=gate();var release=gate();var owner=new AtomicReference<Map<String,Object>>();
        var a=job("ks-holder",()->tx(()->{references.protectForDraft(company,List.of(i.itemId()));owner.set(physical());held.countDown();await(release);return true;}));await(held);
        // A second provider KS and an isolated non-key SQL UPDATE both complete while A still holds KS.
        assertThat(result(job("ks-second",()->tx(()->references.protectForDraft(company,List.of(i.itemId())))))).isInstanceOf(List.class);
        assertThat(result(job("non-key-control",()->tx(()->jdbc.update("update masterdata_item set name='control non-key update' where id=?",i.itemId()))))).isEqualTo(1);
        assertThat(a.isDone()).isFalse();release.countDown();assertThat(result(a)).isEqualTo(true);assertThat(latest(i).version()).isZero();assertThat(latest(i).firstUsedAt()).isNull();
        System.out.println("ITEM KS control: two KS coexist and raw non-key UPDATE completes; production Item mutation still takes stronger U");
    }
    @Test void exactTupleModesAndPostgresUuidOrderBeforeConsumerDml() {
        var low=item("LOW");var high=item("HIGH");UUID lowId=UUID.fromString("00000000-0000-4000-8000-000000000001"),highId=UUID.fromString("80000000-0000-4000-8000-000000000001");
        jdbc.update("update masterdata_item set id=? where id=?",lowId,low.itemId());jdbc.update("update masterdata_item set id=? where id=?",highId,high.itemId());
        jdbc.execute("create extension if not exists pgrowlocks");
        jdbc.execute("create table if not exists org03_probe.item_reference_probe(id uuid primary key,item_id uuid references masterdata_item(id))");
        var held=gate();var release=gate();var owner=new AtomicReference<Map<String,Object>>();
        var a=job("sorted-holder",()->tx(()->{owner.set(physical());var refs=references.protectForDraft(company,List.of(highId,lowId,highId));assertThat(refs).extracting(ItemReference::itemId).containsExactly(lowId,highId);owner.set(physical());jdbc.update("insert into org03_probe.item_reference_probe values(?,?)",UUID.randomUUID(),lowId);held.countDown();await(release);return refs;}));await(held);
        var modes=jdbc.queryForList("select i.id,l.modes::text modes from pgrowlocks('masterdata_item') l join masterdata_item i on i.ctid=l.locked_row order by i.id");
        assertThat(modes).hasSize(2).allSatisfy(r->assertThat(r.get("modes").toString()).contains("Key Share"));
        var b=job("reverse-order-waiter",()->tx(()->store.lock(company,List.of(lowId,highId),ItemStore.Lock.UPDATE)));blocked("FOR UPDATE",owner.get());release.countDown();assertThat(result(a)).isInstanceOf(List.class);assertThat(result(b)).isInstanceOf(List.class);
        var trace=sql(owner.get());assertThat(trace).contains("order by id FOR KEY SHARE");assertThat(trace.indexOf("FOR KEY SHARE")).isLessThan(trace.indexOf("insert into org03_probe.item_reference_probe"));
        jdbc.execute("drop table org03_probe.item_reference_probe");
        var heldU=gate();var releaseU=gate();var u=job("tuple-U",()->tx(()->{store.lock(company,List.of(lowId),ItemStore.Lock.UPDATE);heldU.countDown();await(releaseU);return true;}));await(heldU);
        assertThat(jdbc.queryForObject("select l.modes::text from pgrowlocks('masterdata_item') l join masterdata_item i on i.ctid=l.locked_row where i.id=?",String.class,lowId)).contains("For Update");releaseU.countDown();assertThat(result(u)).isEqualTo(true);
        System.out.println("ITEM tuple modes="+modes+" DB UUID order differs from Java signed comparator; KS before test-only consumer FK INSERT (not PO)\n"+trace);
    }
    @Test void t22_actualLockTimeoutDoesNotBecomeBusinessConflictOrPartialUse() {
        var i=item("timeout");var held=gate();var release=gate();var owner=new AtomicReference<Map<String,Object>>();
        var a=job("timeout-owner",()->tx(()->{references.protectForDraft(company,List.of(i.itemId()));owner.set(physical());held.countDown();await(release);return true;}));await(held);
        var error=catchThrowable(()->tx(()->{jdbc.execute("set local lock_timeout='200ms'");return use.lockForNewUse(company,List.of(observed(i)),source());}));
        assertThat(pg(error).getSQLState()).isEqualTo("55P03");assertThat(error).isNotInstanceOf(ItemFailure.class);assertThat(store.translate((RuntimeException)error)).isSameAs(error);
        release.countDown();assertThat(result(a)).isEqualTo(true);assertThat(latest(i)).isEqualTo(i);System.out.println("ITEM isolated timeout actual SQLSTATE="+pg(error).getSQLState()+" no retry and no first-use write");
    }
    @ParameterizedTest @ValueSource(strings={"name", "base_uom", "usage_profile", "reference_price", "version", "first_use_kind", "status", "id", "company_id"})
    void t19_databaseNegativeCasesHaveExactConstraintNames(String field) {
        var i=item("constraints");var before=row(i);String expression;String constraint;String sqlstate="23514";
        switch(field){
            case "name"->{expression="'bad'||chr(10)";constraint="ck_masterdata_item_name";}
            case "base_uom"->{expression="'LITER'";constraint="ck_masterdata_item_uom";}
            case "usage_profile"->{expression="'INTERNAL_USE'";constraint="ck_masterdata_item_profile";}
            case "reference_price"->{expression="0.5";constraint="ck_masterdata_item_reference_price";}
            case "version"->{expression="-1";constraint="ck_masterdata_item_version";}
            case "first_use_kind"->{expression="'PO_CONFIRMATION'";constraint="ck_masterdata_item_first_use";}
            case "status"->{expression="'DELETED'";constraint="ck_masterdata_item_status";}
            case "id"->{expression="'00000000-0000-0000-0000-000000000000'::uuid";constraint="ck_masterdata_item_id";}
            default->{expression="'11111111-1111-4111-8111-111111111111'::uuid";constraint="fk_masterdata_item_company";sqlstate="23503";}
        }
        var error=catchThrowable(()->jdbc.update("update masterdata_item set "+field+"="+expression+" where id=?",i.itemId()));
        assertThat(pg(error).getSQLState()).isEqualTo(sqlstate);assertThat(pg(error).getServerErrorMessage().getConstraint()).isEqualTo(constraint);assertThat(store.translate((RuntimeException)error)).isSameAs(error);assertThat(row(i)).isEqualTo(before);
        System.out.println("ITEM CHECK/FK negative SQLSTATE="+sqlstate+" constraint="+constraint);
    }
    @ParameterizedTest @ValueSource(strings={"NaN", "Infinity", "-Infinity", "0.1", "-1", "1000000000000"})
    void t09_t19_databaseRejectsNonFiniteAndFractionalPriceWithoutRounding(String value) {
        var i=item("numeric");var error=catchThrowable(()->jdbc.update("update masterdata_item set reference_price=?::numeric where id=?",value,i.itemId()));
        assertThat(pg(error).getSQLState()).isEqualTo("23514");assertThat(pg(error).getServerErrorMessage().getConstraint()).isEqualTo("ck_masterdata_item_reference_price");assertThat(latest(i)).isEqualTo(i);
    }
    @Test void t22_deferredCommitFailureDoesNotReturnSuccess() {
        var i=item("commit-abort");var before=row(i);
        jdbc.execute("create function item_abort_commit() returns trigger language plpgsql as $$ begin raise exception 'test-only commit failure' using errcode='23514';return null;end $$");
        jdbc.execute("create constraint trigger item_abort after update on masterdata_item deferrable initially deferred for each row execute function item_abort_commit()");
        try{var error=catchThrowable(()->state(i,"INACTIVE"));assertThat(pg(error).getSQLState()).isEqualTo("23514");assertThat(error).isNotInstanceOf(ItemFailure.class);assertThat(row(i)).isEqualTo(before);}
        finally{jdbc.execute("drop trigger item_abort on masterdata_item");jdbc.execute("drop function item_abort_commit()");}
    }

    @ParameterizedTest @CsvSource({"price,true", "price,false", "uom,true", "uom,false"})
    void t08_priceAndUomEditsAtSameVersionBothOrders(String first, boolean commit) {
        var i=item("price-race");var held=gate();var release=gate();var owner=new AtomicReference<Map<String,Object>>();
        doAnswer(call->{call.callRealMethod();if(Thread.currentThread().getName().equals("price-owner")){owner.set(physical());held.countDown();await(release);if(!commit)throw new IllegalStateException("test rollback price owner");}return null;}).when(store).update(any(),any());
        Supplier<?> price=()->service.changePrice(new ItemCommands.Price(company,i.itemId(),0L,Unit.EA,BigDecimal.TEN));Supplier<?> uom=()->attributes(i,i.code(),Unit.KG,null);
        var a=job("price-owner",first.equals("price")?price:uom);await(held);var b=job("price-waiter",first.equals("price")?uom:price);blocked("FOR UPDATE",owner.get());release.countDown();var ar=result(a);var br=result(b);
        if(commit){assertThat(ar).isInstanceOf(ItemView.class);assertThat(br).isInstanceOfSatisfying(ItemFailure.class,f->assertThat(f.code()).isEqualTo(VERSION_CONFLICT));}
        else{assertThat((Throwable)ar).isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class).hasRootCauseInstanceOf(IllegalStateException.class).hasMessageContaining("test rollback");assertThat(br).isInstanceOf(ItemView.class);}
        boolean priceWon=first.equals("price")==commit;assertThat(latest(i).baseUom()).isEqualTo(priceWon?Unit.EA:Unit.KG);assertThat(latest(i).referencePrice()).isEqualTo(priceWon?"10":null);assertThat(latest(i).version()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"\u00a0", "\u2003", "x\u0085"})
    void t03_javaAndDatabaseRejectSameUnicodeBlankOrControl(String value) {
        var i=item("unicode");failure(INVALID_INPUT,()->service.describe(new ItemCommands.Describe(company,i.itemId(),0L,value,null)));
        var error=catchThrowable(()->jdbc.update("update masterdata_item set name=? where id=?",value,i.itemId()));
        assertThat(error).isNotNull();assertThat(pg(error).getServerErrorMessage().getConstraint()).isEqualTo("ck_masterdata_item_name");assertThat(latest(i)).isEqualTo(i);
    }
    @Test void t22_negativeConsumerLockInversionHasDistinctDeadlockSqlstateAndRootRollback() {
        var aItem=item("DEAD-A");var bItem=item("DEAD-B");var aHeld=gate();var bHeld=gate();var bRequest=gate();var bPhysical=new AtomicReference<Map<String,Object>>();
        var a=job("deadlock-a",()->tx(()->{use.lockForNewUse(company,List.of(observed(aItem)),source());aHeld.countDown();await(bHeld);return use.lockForNewUse(company,List.of(observed(bItem)),source());}));
        await(aHeld);
        var b=job("deadlock-b",()->tx(()->{use.lockForNewUse(company,List.of(observed(bItem)),source());bPhysical.set(physical());bHeld.countDown();await(bRequest);return use.lockForNewUse(company,List.of(observed(aItem)),source());}));
        await(bHeld);blocked("FOR UPDATE",bPhysical.get());bRequest.countDown();var results=List.of(result(a),result(b));var failures=results.stream().filter(Throwable.class::isInstance).map(Throwable.class::cast).toList();
        assertThat(failures).hasSize(1);assertThat(pg(failures.getFirst()).getSQLState()).isEqualTo("40P01");assertThat(failures.getFirst()).isNotInstanceOf(ItemFailure.class);
        assertThat(latest(aItem).version()).isEqualTo(1);assertThat(latest(bItem).version()).isEqualTo(1);
        System.out.println("ITEM isolated invalid consumer two-call reverse lock graph => actual 40P01; victim root marker rolled back, no automatic retry. Consumers must submit the complete sorted Item set once.");
    }
}
