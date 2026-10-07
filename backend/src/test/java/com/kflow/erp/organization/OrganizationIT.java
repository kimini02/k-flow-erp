package com.kflow.erp.organization;

import com.kflow.erp.KFlowErpApplication;
import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.application.*;
import com.kflow.erp.organization.domain.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.kflow.erp.organization.api.OrganizationFailure.Code.*;

@Testcontainers
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganizationIT {
    @Container @ServiceConnection static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");
    @Autowired OrganizationService service;
    @Autowired OrganizationReferenceQueries references;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean OrganizationStore store;
    Map<String,Object> seed;
    UUID companyId;
    @BeforeAll void captureMigrationSeed() {
        seed = jdbc.queryForMap("select * from organization_company");
        companyId = (UUID) seed.get("id");
    }
    @BeforeEach void restoreIsolatedFixture() {
        reset(store);
        jdbc.update("delete from organization_site");
        jdbc.update("delete from organization_company");
        jdbc.update("insert into organization_company values (?, ?, ?, ?, ?, ?, ?)", seed.get("id"), seed.get("singleton_key"),
                seed.get("code"), seed.get("name"), seed.get("version"), seed.get("created_at"), seed.get("updated_at"));
    }
    Map<String,Object> companyRow() { return jdbc.queryForMap("select * from organization_company"); }
    Map<String,Object> siteRow(UUID id) { return jdbc.queryForMap("select * from organization_site where id = ?", id); }
    void failure(OrganizationFailure.Code code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(OrganizationFailure.class, e -> assertThat(e.code()).isEqualTo(code));
    }
    ConfigurableApplicationContext restart() {
        return new SpringApplicationBuilder(KFlowErpApplication.class).web(WebApplicationType.NONE)
                .properties("spring.datasource.url=" + postgres.getJdbcUrl(), "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(), "spring.main.banner-mode=off")
                .run();
    }
    @Test void migrationSeedAndRepeatedStartupPreserveCompany() {
        assertThat(companyId.version()).isEqualTo(4);
        assertThat(companyId.toString()).isEqualTo("c04b2a3e-6c22-43a7-84f1-d7df625cb826");
        assertThat(seed.get("code")).isEqualTo("HANGYEOL");
        assertThat(seed.get("name")).isEqualTo("한결 인더스트리");
        assertThat(((Number)seed.get("singleton_key")).intValue()).isEqualTo(1);
        assertThat(seed.get("version")).isEqualTo(0L);
        assertThat(seed.get("created_at")).isEqualTo(seed.get("updated_at"));
        assertThat(jdbc.queryForObject("select count(*) from organization_site", Long.class)).isZero();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try(var context = restart()) { assertThat(context.getBean(OrganizationReferenceQueries.class).getCompany().companyId()).isEqualTo(companyId); }
        assertThat(companyRow()).isEqualTo(seed);
        System.out.println("ORG-EVIDENCE migration/restart company=" + companyRow() + ", sites=0");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().pending()).isEmpty();
    }
    @Test void renamedCompanySurvivesRestartAndRead() {
        var renamed = service.renameCompany(companyId, "  새 표시명  ", 0);
        var saved = companyRow();
        assertThat(renamed.name()).isEqualTo("새 표시명"); assertThat(renamed.version()).isEqualTo(1);
        assertThat(saved.get("id")).isEqualTo(seed.get("id")); assertThat(saved.get("code")).isEqualTo(seed.get("code"));
        assertThat(saved.get("created_at")).isEqualTo(seed.get("created_at"));
        assertThat(saved.get("updated_at")).isNotEqualTo(seed.get("updated_at"));
        try(var context=restart()) { assertThat(context.getBean(OrganizationReferenceQueries.class).getCompany()).isEqualTo(renamed); }
        assertThat(references.getCompany()).isEqualTo(renamed); assertThat(companyRow()).isEqualTo(saved);
    }
    @Test void missingCompanyFailsRuntimeAndStartupWithoutReseeding() {
        jdbc.update("delete from organization_company");
        failure(COMPANY_CONFIGURATION_INVALID, service::getCompany);
        failure(COMPANY_CONFIGURATION_INVALID, () -> service.registerSite("HQ", "본사"));
        failure(COMPANY_CONFIGURATION_INVALID, () -> service.listSites(companyId, null, 0, null));
        failure(COMPANY_CONFIGURATION_INVALID, this::restart);
        assertThat(jdbc.queryForObject("select count(*) from organization_company", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from organization_site", Long.class)).isZero();
    }
    @Test void alternativeValidCompanyFixtureWorksWithoutSeedConstants() {
        UUID alternate = UUID.randomUUID();
        jdbc.update("update organization_company set id=?, code='ALTERNATE', name='격리 회사'", alternate);
        try(var context = restart()) {
            var commands = context.getBean(OrganizationService.class);
            assertThat(commands.getCompany().companyId()).isEqualTo(alternate);
            assertThat(commands.getCompany().code()).isEqualTo("ALTERNATE");
            assertThat(commands.registerSite("HQ", "본사").companyId()).isEqualTo(alternate);
        }
        assertThat(jdbc.queryForObject("select company_id from organization_site", UUID.class)).isEqualTo(alternate);
        failure(COMPANY_NOT_FOUND, () -> service.findSite(companyId, UUID.randomUUID()));
    }
    @Test void multipleCompanyContractRejectsInsteadOfChoosingFirst() {
        var real = store.companies();
        doReturn(List.of(real.getFirst(), real.getFirst())).when(store).companies();
        failure(COMPANY_CONFIGURATION_INVALID, service::getCompany);
        failure(COMPANY_CONFIGURATION_INVALID, () -> service.registerSite("HQ", "본사"));
    }
    @ParameterizedTest @ValueSource(strings={"1", "2", "NULL"})
    void secondCompanyAndSingletonBypassRollback(String slot) {
        var before = companyRow();
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(tx -> {
            jdbc.update("update organization_company set name='롤백 대상'");
            jdbc.update("insert into organization_company values (?, " + slot + ", 'OTHER', '다른 회사', 0, now(), now())", UUID.randomUUID());
            return null;
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(companyRow()).isEqualTo(before);
    }
    @Test void canonicalCodesInactiveDuplicateNamesAndNamespaces() {
        var hq = service.registerSite("  hq  ", "본사");
        assertThat(hq.code()).isEqualTo("HQ"); assertThat(hq.status()).isEqualTo("ACTIVE");
        assertThat(hq.siteId().version()).isEqualTo(4); assertThat(hq.version()).isZero();
        var before = siteRow(hq.siteId());
        for (String code : List.of("Hq", "HQ", " hq ")) failure(SITE_CODE_CONFLICT, () -> service.registerSite(code, "본사"));
        assertThat(siteRow(hq.siteId())).isEqualTo(before);
        jdbc.update("update organization_site set status='INACTIVE' where id=?", hq.siteId());
        failure(SITE_CODE_CONFLICT, () -> service.registerSite("HQ", "다른 이름"));
        assertThat(references.findSite(companyId, hq.siteId()).orElseThrow().status()).isEqualTo("INACTIVE");
        var other = service.registerSite("OTHER", "본사"); assertThat(other.siteId()).isNotEqualTo(hq.siteId());
        assertThat(service.registerSite(service.getCompany().code(), "회사 코드와 같음").code()).isEqualTo(service.getCompany().code());
        assertThat(service.registerSite("HQ-1", "구분").code()).isNotEqualTo(service.registerSite("HQ_1", "구분").code());
    }
    @Test void invalidInputsDoNotPersistAndUnicodeBoundaryPersists() {
        for (String code : Arrays.asList(null,"", "\tHQ", "ＨＱ", "HQ\n", "H Q", "a".repeat(33)))
            failure(INVALID_ORGANIZATION_CODE, () -> service.registerSite(code, "본사"));
        for (String name : Arrays.asList(null,"", " ", "\u00a0", "이름\n", "😀".repeat(101)))
            failure(INVALID_ORGANIZATION_NAME, () -> service.registerSite("VALID", name));
        assertThat(jdbc.queryForObject("select count(*) from organization_site", Long.class)).isZero();
        var row = service.registerSite("a".repeat(32), "😀".repeat(100));
        assertThat(siteRow(row.siteId()).get("name")).isEqualTo("😀".repeat(100));
    }
    @ParameterizedTest @ValueSource(strings={"company", "site"})
    void databaseRequiredFieldsAndChecksRollback(String table) {
        UUID id = table.equals("site") ? service.registerSite("HQ", "본사").siteId() : companyId;
        String full = "organization_" + table;
        var original = jdbc.queryForMap("select * from " + full + " where id=?", id);
        var invalid = new ArrayList<>(List.of("id=NULL", "code=NULL", "name=NULL", "version=NULL", "created_at=NULL", "updated_at=NULL",
                "code='hq'", "code='H Q'", "code='ＨＱ'", "code=''", "name=''", "name=' '", "version=-1", "code=repeat('A',33)", "name=repeat('가',101)"));
        if (table.equals("site")) invalid.addAll(List.of("company_id=NULL", "company_id='00000000-0000-4000-8000-000000000000'", "status=NULL", "status='PENDING'"));
        else invalid.addAll(List.of("singleton_key=NULL", "singleton_key=2"));
        for (String change : invalid) {
            assertThatThrownBy(() -> new TransactionTemplate(manager).execute(tx -> {
                jdbc.update("update " + full + " set name='rollback'");
                jdbc.update("update " + full + " set " + change + " where id=?", id); return null;
            })).as(full + ":" + change).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(jdbc.queryForMap("select * from " + full + " where id=?", id)).isEqualTo(original);
        }
        assertThatThrownBy(() -> jdbc.update("insert into " + full + " select * from " + full)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from " + full, Long.class)).isEqualTo(1L);
    }
    @Test void referencingSiteRestrictsCompanyDeleteAndIdChange() {
        service.registerSite("HQ", "본사");
        assertThatThrownBy(() -> jdbc.update("delete from organization_company")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update organization_company set id=?", UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(companyRow()).isEqualTo(seed);
        assertThat(jdbc.queryForObject("select company_id from organization_site", UUID.class)).isEqualTo(companyId);
    }
    @Test void siteRenameStableIdentityAndNoopWithoutWrite() {
        var site = service.registerSite("HQ", "본사"); var initial = siteRow(site.siteId());
        var renamed = service.renameSite(companyId, site.siteId(), "  새 본사  ", 0);
        var saved = siteRow(site.siteId());
        assertThat(renamed.name()).isEqualTo("새 본사"); assertThat(renamed.version()).isEqualTo(1);
        for(String field : List.of("id", "company_id", "code", "created_at", "status")) assertThat(saved.get(field)).isEqualTo(initial.get(field));
        assertThat(saved.get("updated_at")).isNotEqualTo(initial.get("updated_at"));
        var xmin = jdbc.queryForObject("select xmin::text from organization_site where id=?", String.class, site.siteId());
        assertThat(service.renameSite(companyId, site.siteId(), " 새 본사 ", 1)).isEqualTo(renamed);
        failure(VERSION_CONFLICT, () -> service.renameSite(companyId, site.siteId(), "새 본사", 0));
        failure(INVALID_VERSION, () -> service.renameSite(companyId, site.siteId(), "새 본사", -1));
        assertThat(siteRow(site.siteId())).isEqualTo(saved);
        assertThat(jdbc.queryForObject("select xmin::text from organization_site where id=?", String.class, site.siteId())).isEqualTo(xmin);
        System.out.println("ORG-EVIDENCE site no-op/stale unchanged xmin=" + xmin + ", row=" + saved);
        failure(SITE_NOT_FOUND, () -> service.renameSite(companyId, UUID.randomUUID(), "없는 장소", 0));
    }
    @Test void companyNoopWithoutWriteAndStaleRejected() {
        var renamed = service.renameCompany(companyId, "정정", 0); var before=companyRow();
        var xmin=jdbc.queryForObject("select xmin::text from organization_company", String.class);
        assertThat(service.renameCompany(companyId, " 정정 ", 1)).isEqualTo(renamed);
        failure(VERSION_CONFLICT, () -> service.renameCompany(companyId, "정정", 0));
        assertThat(companyRow()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select xmin::text from organization_company", String.class)).isEqualTo(xmin);
        System.out.println("ORG-EVIDENCE company no-op/stale unchanged xmin=" + xmin + ", row=" + before);
    }
    @Test void missingEmptyInvalidCompanyAndDatabaseFailureAreDistinct() {
        assertThat(service.findSite(companyId, UUID.randomUUID())).isEmpty();
        assertThat(service.listSites(companyId, null, 0, null).items()).isEmpty();
        failure(COMPANY_NOT_FOUND, () -> service.findSite(UUID.randomUUID(), UUID.randomUUID()));
        failure(COMPANY_NOT_FOUND, () -> service.listSites(UUID.randomUUID(), null, 0, null));
        // Real SQL error in a rollback-only test transaction, not a mocked repository exception.
        new TransactionTemplate(manager).execute(tx -> {
            jdbc.execute("alter table organization_site rename to temporarily_unavailable_site");
            assertThatThrownBy(() -> service.findSite(companyId, UUID.randomUUID())).isInstanceOf(org.springframework.dao.DataAccessException.class);
            tx.setRollbackOnly(); return null;
        });
        new TransactionTemplate(manager).execute(tx -> {
            jdbc.execute("alter table organization_site rename to temporarily_unavailable_site");
            assertThatThrownBy(() -> service.listSites(companyId, null, 0, null)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            tx.setRollbackOnly(); return null;
        });
        assertThat(service.listSites(companyId, null, 0, null).items()).isEmpty();
    }
    @Test void internalPaginationFiltersAndBoundaries() {
        for(int i=21;i>=0;i--) service.registerSite(String.format("S%02d",i), "같은 이름");
        jdbc.update("update organization_site set status='INACTIVE' where code='S00'");
        var first=service.listSites(companyId,null,0,null);
        assertThat(first.pageSize()).isEqualTo(20); assertThat(first.pageIndex()).isZero(); assertThat(first.hasNext()).isTrue();
        assertThat(first.items()).hasSize(20); assertThat(first.items().getFirst().code()).isEqualTo("S00");
        assertThat(first.items().stream().map(SiteListItem::code).toList()).isSorted();
        assertThat(service.listSites(companyId,null,1,20).items()).extracting(SiteListItem::code).containsExactly("S20","S21");
        assertThat(service.listSites(companyId,null,1,20).hasNext()).isFalse();
        assertThat(service.listSites(companyId,null,2,20).items()).isEmpty();
        assertThat(service.listSites(companyId,"ACTIVE",0,100).items()).hasSize(21);
        assertThat(service.listSites(companyId,"INACTIVE",0,1).items()).extracting(SiteListItem::code).containsExactly("S00");
        assertThat(service.listSites(companyId,null,0,1).hasNext()).isTrue();
        failure(INVALID_PAGE, () -> service.listSites(companyId,null,-1,20));
        failure(INVALID_PAGE, () -> service.listSites(companyId,null,0,0));
        failure(INVALID_PAGE, () -> service.listSites(companyId,null,0,101));
        failure(INVALID_PAGE, () -> service.listSites(companyId,"UNKNOWN",0,20));
    }
    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(15, TimeUnit.SECONDS)).as("concurrency rendezvous").isTrue(); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    static Object outcome(Supplier<?> command) {
        try { return command.get(); } catch (OrganizationFailure failure) { return failure.code(); }
    }
    List<Object> race(Supplier<?> first, Supplier<?> second) throws Exception {
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(() -> outcome(first)); var b=executor.submit(() -> outcome(second));
            return List.of(a.get(25,TimeUnit.SECONDS),b.get(25,TimeUnit.SECONDS));
        }
    }
    @Test void concurrentCanonicalCodeRegistrationCommitsExactlyOne() throws Exception {
        var ready = new CountDownLatch(2);
        doAnswer(call -> { var result=call.callRealMethod(); ready.countDown(); await(ready); return result; }).when(store).companies();
        var results = race(() -> service.registerSite("hq", "동일 이름"), () -> service.registerSite("HQ", "동일 이름"));
        assertThat(results.stream().filter(SiteReference.class::isInstance)).hasSize(1);
        assertThat(results).contains(SITE_CODE_CONFLICT);
        System.out.println("ORG-EVIDENCE code race outcomes=" + results);
        assertThat(jdbc.queryForObject("select count(*) from organization_site where code='HQ'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select version from organization_site", Long.class)).isZero();
        assertThat(companyRow()).isEqualTo(seed);
    }
    @ParameterizedTest @ValueSource(strings={"company", "site"})
    void concurrentRenamesHaveOneWinnerAndRollbackLoser(String type) throws Exception {
        var site=service.registerSite("HQ", "본사"); var ready=new CountDownLatch(2);
        if(type.equals("company")) doAnswer(call -> { var result=call.callRealMethod(); ready.countDown(); await(ready); return result; }).when(store).companies();
        else doAnswer(call -> { var result=call.callRealMethod(); ready.countDown(); await(ready); return result; }).when(store).site(companyId,site.siteId());
        var results=race(() -> rename(type, site.siteId(), "승자A"), () -> rename(type, site.siteId(), "승자B"));
        assertThat(results.stream().filter(OrganizationFailure.Code.class::isInstance)).containsExactly(VERSION_CONFLICT);
        var row=type.equals("company")?companyRow():siteRow(site.siteId());
        assertThat(row.get("version")).isEqualTo(1L);
        var winner=results.stream().filter(r -> !(r instanceof OrganizationFailure.Code)).findFirst().orElseThrow();
        String winnerName=winner instanceof CompanyReference c?c.name():((SiteReference)winner).name();
        assertThat(row.get("name")).isEqualTo(winnerName);
        System.out.println("ORG-EVIDENCE " + type + " rename race outcomes=" + results + ", committed=" + row);
    }
    Object rename(String type, UUID siteId, String name) {
        return type.equals("company")?service.renameCompany(companyId,name,0):service.renameSite(companyId,siteId,name,0);
    }
    @ParameterizedTest @ValueSource(strings={"company", "site"})
    void noopChecksVersionAtCommitAndRollsBackConcurrentStaleRead(String type) throws Exception {
        var site=service.registerSite("HQ", "본사"); var locked=new CountDownLatch(1); var proceed=new CountDownLatch(1);
        String unchanged=type.equals("company")?service.getCompany().name():site.name();
        // Synchronize only the no-op transaction after its actual JPA OPTIMISTIC lock request.
        var answer=new org.mockito.stubbing.Answer<Object>() {
            public Object answer(org.mockito.invocation.InvocationOnMock call) throws Throwable {
                Object value=call.callRealMethod();
                if(Thread.currentThread().getName().equals("org-noop")) { locked.countDown(); await(proceed); }
                return value;
            }
        };
        if(type.equals("company")) doAnswer(answer).when(store).verifyAtCommit(any(Company.class));
        else doAnswer(answer).when(store).verifyAtCommit(any(Site.class));
        try(var executor=Executors.newSingleThreadExecutor(r -> new Thread(r,"org-noop"))) {
            var noOp=executor.submit(() -> outcome(() -> rename(type,site.siteId(),unchanged)));
            await(locked);
            try { rename(type,site.siteId(),"경쟁 변경"); } finally { proceed.countDown(); }
            assertThat(noOp.get(25,TimeUnit.SECONDS)).isEqualTo(VERSION_CONFLICT);
        }
        var row=type.equals("company")?companyRow():siteRow(site.siteId());
        assertThat(row.get("version")).isEqualTo(1L); assertThat(row.get("name")).isEqualTo("경쟁 변경");
        System.out.println("ORG-EVIDENCE " + type + " no-op stale rejected at commit; committed=" + row);
    }
}
