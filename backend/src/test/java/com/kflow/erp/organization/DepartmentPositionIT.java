package com.kflow.erp.organization;

import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.application.*;
import com.kflow.erp.organization.domain.*;
import com.kflow.erp.KFlowErpApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.kflow.erp.organization.api.OrganizationFailure.Code.*;

@Testcontainers
@SpringBootTest(classes = KFlowErpApplication.class)
class DepartmentPositionIT {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");
    @Autowired OrganizationService service;
    @Autowired OrganizationReferenceQueries queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired Flyway flyway;
    @MockitoSpyBean OrganizationStore store;
    UUID company;
    enum Kind { DEPARTMENT, POSITION;
        String table() { return "organization_" + name().toLowerCase(Locale.ROOT); }
        OrganizationFailure.Code conflict() { return this == DEPARTMENT ? DEPARTMENT_CODE_CONFLICT : POSITION_CODE_CONFLICT; }
        OrganizationFailure.Code missing() { return this == DEPARTMENT ? DEPARTMENT_NOT_FOUND : POSITION_NOT_FOUND; }
    }
    record Ref(UUID id, UUID companyId, String code, String name, String status, long version) {}
    record Page(List<Ref> items, int index, int size, boolean hasNext) {}
    Ref ref(DepartmentReference r) { return new Ref(r.departmentId(),r.companyId(),r.code(),r.name(),r.status(),r.version()); }
    Ref ref(PositionReference r) { return new Ref(r.positionId(),r.companyId(),r.code(),r.name(),r.status(),r.version()); }
    Ref register(Kind k, String code, String name) {
        return k == Kind.DEPARTMENT ? ref(service.registerDepartment(code,name)) : ref(service.registerPosition(code,name));
    }
    Ref rename(Kind k, UUID companyId, UUID id, String name, Long version) {
        return k == Kind.DEPARTMENT ? ref(service.renameDepartment(companyId,id,name,version)) : ref(service.renamePosition(companyId,id,name,version));
    }
    Optional<Ref> find(Kind k, UUID companyId, UUID id) {
        return k == Kind.DEPARTMENT ? queries.findDepartment(companyId,id).map(this::ref) : queries.findPosition(companyId,id).map(this::ref);
    }
    Page list(Kind k, UUID companyId, String status, int index, Integer size) {
        if (k == Kind.DEPARTMENT) {
            var p=service.listDepartments(companyId,status,index,size);
            return new Page(p.items().stream().map(r -> new Ref(r.departmentId(),r.companyId(),r.code(),r.name(),r.status(),r.version())).toList(),p.pageIndex(),p.pageSize(),p.hasNext());
        }
        var p=service.listPositions(companyId,status,index,size);
        return new Page(p.items().stream().map(r -> new Ref(r.positionId(),r.companyId(),r.code(),r.name(),r.status(),r.version())).toList(),p.pageIndex(),p.pageSize(),p.hasNext());
    }
    Map<String,Object> row(Kind k, UUID id) { return jdbc.queryForMap("select * from "+k.table()+" where id=?",id); }
    long count(Kind k) { return jdbc.queryForObject("select count(*) from "+k.table(),Long.class); }
    void failure(OrganizationFailure.Code code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(OrganizationFailure.class,e -> assertThat(e.code()).isEqualTo(code));
    }
    @BeforeEach void cleanFixture() {
        reset(store);
        jdbc.update("delete from organization_department");
        jdbc.update("delete from organization_position");
        company=queries.getCompany().companyId();
    }
    @Test void migrationAndIndependentNamespacesPreserveCompanyAndSite() {
        var before=jdbc.queryForMap("select * from organization_company");
        assertThat(jdbc.queryForObject("select count(*) from organization_site",Long.class)).isZero();
        var d=register(Kind.DEPARTMENT," common ","동명");
        var p=register(Kind.POSITION,"common","동명");
        for (var r: List.of(d,p)) {
            assertThat(r.id().version()).isEqualTo(4); assertThat(r.companyId()).isEqualTo(company);
            assertThat(r.code()).isEqualTo("COMMON"); assertThat(r.status()).isEqualTo("ACTIVE"); assertThat(r.version()).isZero();
        }
        assertThat(d.id()).isNotEqualTo(p.id());
        assertThat(find(Kind.DEPARTMENT,company,p.id())).isEmpty();
        assertThat(find(Kind.POSITION,company,d.id())).isEmpty();
        assertThat(jdbc.queryForMap("select * from organization_company")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from organization_site",Long.class)).isZero();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("6");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void codesNamesInactiveReuseAndRegistrationRetry(Kind k) {
        var first=register(k," common ","같은 이름"); var saved=row(k,first.id());
        // A committed registration whose response was lost: retry the original request, not a rolled-back insert.
        failure(k.conflict(),()->register(k,"COMMON","다른 이름"));
        assertThat(row(k,first.id())).isEqualTo(saved); assertThat(count(k)).isEqualTo(1);
        jdbc.update("update "+k.table()+" set status='INACTIVE' where id=?",first.id());
        failure(k.conflict(),()->register(k,"common","같은 이름"));
        assertThat(find(k,company,first.id()).orElseThrow().status()).isEqualTo("INACTIVE");
        var renamed=rename(k,company,first.id()," 정정 ",0L);
        assertThat(renamed.status()).isEqualTo("INACTIVE"); assertThat(renamed.id()).isEqualTo(first.id());
        assertThat(renamed.name()).isEqualTo("정정"); assertThat(renamed.version()).isEqualTo(1);
        assertThat(register(k,"OTHER","정정").id()).isNotEqualTo(first.id());
        assertThat(register(k,"001","정정").code()).isEqualTo("001");
        assertThat(register(k,"SALES-1","정정").code()).isNotEqualTo(register(k,"SALES_1","정정").code());
        assertThatThrownBy(()->jdbc.update("insert into "+k.table()+" (id,company_id,code,name,status,version,created_at,updated_at) select ?,company_id,code,name,status,version,created_at,updated_at from "+k.table()+" where id=?",UUID.randomUUID(),first.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void invalidValuesRollbackAndUnicodeBoundariesPersist(Kind k) {
        for(String code:Arrays.asList(null,"","\tA","A\n","A\r","\u00a0A","A B","Ａ","한글","A".repeat(33)))
            failure(INVALID_ORGANIZATION_CODE,()->register(k,code,"이름"));
        for(String name:Arrays.asList(null,""," ","\u00a0","이름\n","😀".repeat(101)))
            failure(INVALID_ORGANIZATION_NAME,()->register(k,"VALID",name));
        assertThat(count(k)).isZero();
        var good=register(k,"a".repeat(32),"😀".repeat(100)); var before=row(k,good.id());
        assertThat(good.code()).isEqualTo("A".repeat(32));
        assertThat(before.get("name")).isEqualTo("😀".repeat(100));
        for(String name:Arrays.asList(null,"","\t","이름\n","😀".repeat(101)))
            failure(INVALID_ORGANIZATION_NAME,()->rename(k,company,good.id(),name,0L));
        assertThat(row(k,good.id())).isEqualTo(before);
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void renameStableIdentityNoopStaleAndLostResponseRetry(Kind k) {
        var initial=register(k,"A","이름"); var before=row(k,initial.id());
        var changed=rename(k,company,initial.id()," 새 이름 ",0L); var saved=row(k,initial.id());
        for(String field:List.of("id","company_id","code","status","created_at")) assertThat(saved.get(field)).isEqualTo(before.get(field));
        assertThat(changed.version()).isEqualTo(1); assertThat(saved.get("version")).isEqualTo(changed.version());
        assertThat(saved.get("updated_at")).isNotEqualTo(before.get("updated_at"));
        String xmin=jdbc.queryForObject("select xmin::text from "+k.table()+" where id=?",String.class,initial.id());
        assertThat(rename(k,company,initial.id()," 새 이름 ",1L)).isEqualTo(changed);
        // Successful rename response lost: same payload and old version must still conflict.
        failure(VERSION_CONFLICT,()->rename(k,company,initial.id(),"새 이름",0L));
        failure(VERSION_CONFLICT,()->rename(k,company,initial.id(),"다른 이름",0L));
        failure(INVALID_VERSION,()->rename(k,company,initial.id(),"이름",null));
        failure(INVALID_VERSION,()->rename(k,company,initial.id(),"이름",-1L));
        failure(k.missing(),()->rename(k,company,UUID.randomUUID(),"이름",0L));
        assertThat(row(k,initial.id())).isEqualTo(saved);
        assertThat(jdbc.queryForObject("select xmin::text from "+k.table()+" where id=?",String.class,initial.id())).isEqualTo(xmin);
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void companyErrorsMissingAndDatabaseFailuresAreNotEmpty(Kind k) {
        assertThat(find(k,company,UUID.randomUUID())).isEmpty();
        assertThat(list(k,company,null,0,null).items()).isEmpty();
        UUID other=UUID.randomUUID();
        failure(COMPANY_NOT_FOUND,()->find(k,other,UUID.randomUUID()));
        failure(COMPANY_NOT_FOUND,()->rename(k,other,UUID.randomUUID(),"이름",0L));
        failure(COMPANY_NOT_FOUND,()->list(k,other,null,0,null));
        var companies=store.companies();
        // Corrupt singleton cardinality cannot be inserted through the normal DB constraints.
        for(var fixture:List.of(List.<Company>of(),List.of(companies.getFirst(),companies.getFirst()))) {
            doReturn(fixture).when(store).companies();
            failure(COMPANY_CONFIGURATION_INVALID,()->find(k,company,UUID.randomUUID()));
            failure(COMPANY_CONFIGURATION_INVALID,()->register(k,"A","이름"));
            failure(COMPANY_CONFIGURATION_INVALID,()->rename(k,company,UUID.randomUUID(),"이름",0L));
            failure(COMPANY_CONFIGURATION_INVALID,()->list(k,company,null,0,null));
        }
        reset(store);
        new TransactionTemplate(manager).execute(tx->{
            jdbc.update("delete from organization_company");
            failure(COMPANY_CONFIGURATION_INVALID,()->register(k,"A","이름"));
            failure(COMPANY_CONFIGURATION_INVALID,()->find(k,company,UUID.randomUUID()));
            tx.setRollbackOnly(); return null;
        });
        new TransactionTemplate(manager).execute(tx->{
            jdbc.execute("alter table "+k.table()+" rename to temporarily_unavailable");
            assertThatThrownBy(()->find(k,company,UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataAccessException.class);
            tx.setRollbackOnly(); return null;
        });
        assertThat(find(k,company,UUID.randomUUID())).isEmpty();
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void databaseChecksRequiredFieldsAndForeignKeysRollback(Kind k) {
        var r=register(k,"A","이름"); var original=row(k,r.id());
        var invalid=List.of("id=NULL","company_id=NULL","code=NULL","name=NULL","status=NULL","version=NULL","created_at=NULL","updated_at=NULL",
                "company_id='00000000-0000-4000-8000-000000000000'","code='a'","code='A B'","code='Ａ'","code=''","code=repeat('A',33)",
                "name=''","name=' '","name=' x'","name=repeat('가',101)","status='PENDING'","version=-1");
        for(String change:invalid) {
            assertThatThrownBy(()->new TransactionTemplate(manager).execute(tx->{
                jdbc.update("update "+k.table()+" set name='rollback' where id=?",r.id());
                jdbc.update("update "+k.table()+" set "+change+" where id=?",r.id()); return null;
            })).as(k+":"+change).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(row(k,r.id())).isEqualTo(original);
        }
        assertThatThrownBy(()->jdbc.update("insert into "+k.table()+" select * from "+k.table())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("delete from organization_company")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update organization_company set id=?",UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(row(k,r.id())).isEqualTo(original);
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void internalPaginationHasStableOrderFiltersAndInputLimits(Kind k) {
        for(int i=21;i>=0;i--) register(k,String.format("K%02d",i),"같은 이름");
        jdbc.update("update "+k.table()+" set status='INACTIVE' where code='K00'");
        var first=list(k,company,null,0,null); var second=list(k,company,null,1,20);
        assertThat(first.size()).isEqualTo(20); assertThat(first.index()).isZero(); assertThat(first.hasNext()).isTrue();
        var all=new ArrayList<>(first.items()); all.addAll(second.items());
        assertThat(all).hasSize(22).extracting(Ref::code).containsExactly(java.util.stream.IntStream.range(0,22).mapToObj(i->String.format("K%02d",i)).toArray(String[]::new));
        assertThat(all).extracting(Ref::id).doesNotHaveDuplicates();
        assertThat(second.hasNext()).isFalse(); assertThat(list(k,company,null,2,20).items()).isEmpty();
        assertThat(list(k,company,"ACTIVE",0,100).items()).hasSize(21);
        assertThat(list(k,company,"INACTIVE",0,1).items()).extracting(Ref::code).containsExactly("K00");
        assertThat(list(k,company,null,0,1).hasNext()).isTrue();
        failure(INVALID_PAGE,()->list(k,company,null,-1,20));
        failure(INVALID_PAGE,()->list(k,company,null,0,0));
        failure(INVALID_PAGE,()->list(k,company,null,0,101));
        failure(INVALID_PAGE,()->list(k,company,null,Integer.MAX_VALUE,100));
        failure(INVALID_PAGE,()->list(k,company,"UNKNOWN",0,20));
    }
    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15,TimeUnit.SECONDS)).as("rendezvous").isTrue(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    Object outcome(Supplier<?> action) { try { return action.get(); } catch(OrganizationFailure e) { return e.code(); } }
    List<Object> race(Supplier<?> a, Supplier<?> b) throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var x=pool.submit(()->outcome(a)); var y=pool.submit(()->outcome(b));
            return List.of(x.get(25,TimeUnit.SECONDS),y.get(25,TimeUnit.SECONDS));
        }
    }
    void synchronizeReads(Kind k,UUID id) {
        var ready=new CountDownLatch(2);
        org.mockito.stubbing.Answer<Object> answer=call->{ var r=call.callRealMethod(); ready.countDown(); await(ready); return r; };
        if(k==Kind.DEPARTMENT) doAnswer(answer).when(store).department(company,id);
        else doAnswer(answer).when(store).position(company,id);
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void concurrentCanonicalRegistrationCommitsExactlyOne(Kind k) throws Exception {
        var ready=new CountDownLatch(2);
        doAnswer(call->{var r=call.callRealMethod(); ready.countDown(); await(ready); return r;}).when(store).companies();
        var results=race(()->register(k,"sales","이름"),()->register(k," SALES ","이름"));
        assertThat(results.stream().filter(Ref.class::isInstance)).hasSize(1);
        assertThat(results).contains(k.conflict()); assertThat(count(k)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from "+k.table(),Long.class)).isZero();
        System.out.println("O2-EVIDENCE "+k+" canonical registration: "+results);
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void concurrentRealRenameHasOneWinnerAndReturnsCommittedVersion(Kind k) throws Exception {
        var r=register(k,"A","이름"); synchronizeReads(k,r.id());
        var results=race(()->rename(k,company,r.id(),"승자A",0L),()->rename(k,company,r.id(),"승자B",0L));
        assertThat(results.stream().filter(OrganizationFailure.Code.class::isInstance)).containsExactly(VERSION_CONFLICT);
        var winner=(Ref)results.stream().filter(Ref.class::isInstance).findFirst().orElseThrow();
        assertThat(winner.version()).isEqualTo(1); assertThat(row(k,r.id()).get("version")).isEqualTo(winner.version());
        assertThat(row(k,r.id()).get("name")).isEqualTo(winner.name());
        System.out.println("O2-EVIDENCE "+k+" real rename: "+results);
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void concurrentNoopsBothSucceedWithoutChangingRow(Kind k) throws Exception {
        var r=register(k,"A","이름"); var saved=row(k,r.id()); synchronizeReads(k,r.id());
        var results=race(()->rename(k,company,r.id(),"이름",0L),()->rename(k,company,r.id()," 이름 ",0L));
        assertThat(results).containsExactly(r,r); assertThat(row(k,r.id())).isEqualTo(saved);
        System.out.println("O2-EVIDENCE "+k+" concurrent no-op: both success, row unchanged");
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void noopConflictIsTranslatedAtActualIndependentTransactionCompletion(Kind k) throws Exception {
        var r=register(k,"A","이름"); var locked=new CountDownLatch(1); var proceed=new CountDownLatch(1);
        org.mockito.stubbing.Answer<Object> answer=call->{
            var result=call.callRealMethod();
            if(Thread.currentThread().getName().equals("o2-noop")) { locked.countDown(); await(proceed); }
            return result;
        };
        if(k==Kind.DEPARTMENT) doAnswer(answer).when(store).verifyAtCommit(any(Department.class));
        else doAnswer(answer).when(store).verifyAtCommit(any(Position.class));
        try(var pool=Executors.newSingleThreadExecutor(task->new Thread(task,"o2-noop"))) {
            var pending=pool.submit(()->outcome(()->rename(k,company,r.id(),"이름",0L)));
            await(locked);
            try { assertThat(rename(k,company,r.id(),"경쟁 변경",0L).version()).isEqualTo(1); }
            finally { proceed.countDown(); }
            assertThat(pending.get(25,TimeUnit.SECONDS)).isEqualTo(VERSION_CONFLICT);
        }
        assertThat(row(k,r.id()).get("name")).isEqualTo("경쟁 변경");
        assertThat(row(k,r.id()).get("version")).isEqualTo(1L);
        System.out.println("O2-EVIDENCE "+k+" no-op commit-time conflict: VERSION_CONFLICT, competitor committed");
    }
}
