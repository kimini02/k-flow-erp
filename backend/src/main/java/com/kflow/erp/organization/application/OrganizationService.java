package com.kflow.erp.organization.application;

import com.kflow.erp.organization.api.*;
import com.kflow.erp.organization.domain.*;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import static com.kflow.erp.organization.api.OrganizationFailure.Code.*;

@Service
public class OrganizationService implements OrganizationReferenceQueries, SiteListQueries, DepartmentListQueries, PositionListQueries {
    private final OrganizationStore store;
    private final TransactionTemplate read;
    private final TransactionTemplate write;
    public OrganizationService(OrganizationStore store, PlatformTransactionManager manager) {
        this.store = store;
        this.read = new TransactionTemplate(manager); this.read.setReadOnly(true);
        this.write = new TransactionTemplate(manager);
    }
    private Company currentCompany() {
        var rows = store.companies();
        if (rows.size() != 1) throw new OrganizationFailure(COMPANY_CONFIGURATION_INVALID);
        return rows.getFirst();
    }
    private Company company(UUID id) {
        var company = currentCompany();
        if (!company.id().equals(id)) throw new OrganizationFailure(COMPANY_NOT_FOUND);
        return company;
    }
    private static CompanyReference reference(Company c) {
        return new CompanyReference(c.id(), c.code(), c.name(), c.version());
    }
    private static SiteReference reference(Site s) {
        return new SiteReference(s.id(), s.companyId(), s.code(), s.name(), s.status(), s.version());
    }
    public CompanyReference getCompany() { return read.execute(tx -> reference(currentCompany())); }
    public Optional<SiteReference> findSite(UUID companyId, UUID siteId) {
        return read.execute(tx -> { company(companyId); return store.site(companyId, siteId).map(OrganizationService::reference); });
    }
    public SiteReference registerSite(String code, String name) {
        String canonical = OrganizationValues.code(code), display = OrganizationValues.name(name);
        return change(() -> {
            var site = new Site(currentCompany().id(), canonical, display, Instant.now());
            store.add(site); store.flush(); return reference(site);
        });
    }
    public CompanyReference renameCompany(UUID companyId, String name, long expectedVersion) {
        OrganizationValues.expectedVersion(expectedVersion);
        String display = OrganizationValues.name(name);
        return change(() -> {
            var company = company(companyId);
            company.rename(display, expectedVersion, Instant.now());
            store.verifyAtCommit(company); store.flush(); return reference(company);
        });
    }
    public SiteReference renameSite(UUID companyId, UUID siteId, String name, long expectedVersion) {
        OrganizationValues.expectedVersion(expectedVersion);
        String display = OrganizationValues.name(name);
        return change(() -> {
            company(companyId);
            var site = store.site(companyId, siteId).orElseThrow(() -> new OrganizationFailure(SITE_NOT_FOUND));
            site.rename(display, expectedVersion, Instant.now());
            store.verifyAtCommit(site); store.flush(); return reference(site);
        });
    }
    public SitePage listSites(UUID companyId, String status, int pageIndex, Integer pageSize) {
        int size = pageSize == null ? 20 : pageSize;
        if (pageIndex < 0 || size < 1 || size > 100 || (long) pageIndex * size > Integer.MAX_VALUE
                || (status != null && !status.equals("ACTIVE") && !status.equals("INACTIVE")))
            throw new OrganizationFailure(INVALID_PAGE);
        return read.execute(tx -> {
            company(companyId);
            var rows = store.sites(companyId, status, pageIndex * size, size + 1);
            var items = rows.stream().limit(size).map(s -> new SiteListItem(s.id(), s.companyId(),
                    s.code(), s.name(), s.status(), s.version())).toList();
            return new SitePage(items, pageIndex, size, rows.size() > size);
        });
    }

    private static DepartmentReference reference(Department value) {
        return new DepartmentReference(value.id(), value.companyId(), value.code(), value.name(), value.status(), value.version());
    }
    public Optional<DepartmentReference> findDepartment(UUID companyId, UUID departmentId) {
        return read.execute(tx -> { company(companyId); return store.department(companyId, departmentId).map(OrganizationService::reference); });
    }
    public DepartmentReference registerDepartment(String code, String name) {
        String canonical = OrganizationValues.code(code), display = OrganizationValues.name(name);
        return change(() -> {
            var value = new Department(currentCompany().id(), canonical, display, Instant.now());
            store.add(value); store.flush(); return reference(value);
        });
    }
    public DepartmentReference renameDepartment(UUID companyId, UUID departmentId, String name, Long expectedVersion) {
        if (expectedVersion == null) throw new OrganizationFailure(INVALID_VERSION);
        OrganizationValues.expectedVersion(expectedVersion);
        String display = OrganizationValues.name(name);
        return change(() -> {
            company(companyId);
            var value = store.department(companyId, departmentId).orElseThrow(() -> new OrganizationFailure(DEPARTMENT_NOT_FOUND));
            value.rename(display, expectedVersion, Instant.now());
            store.verifyAtCommit(value); store.flush(); return reference(value);
        });
    }
    public DepartmentPage listDepartments(UUID companyId, String status, int pageIndex, Integer pageSize) {
        int size = pageSize == null ? 20 : pageSize;
        if (pageIndex < 0 || size < 1 || size > 100 || (long) pageIndex * size > Integer.MAX_VALUE
                || (status != null && !status.equals("ACTIVE") && !status.equals("INACTIVE")))
            throw new OrganizationFailure(INVALID_PAGE);
        return read.execute(tx -> {
            company(companyId);
            var rows = store.departments(companyId, status, pageIndex * size, size + 1);
            var items = rows.stream().limit(size).map(value -> new DepartmentListItem(value.id(), value.companyId(),
                    value.code(), value.name(), value.status(), value.version())).toList();
            return new DepartmentPage(items, pageIndex, size, rows.size() > size);
        });
    }

    private static PositionReference reference(Position value) {
        return new PositionReference(value.id(), value.companyId(), value.code(), value.name(), value.status(), value.version());
    }
    public Optional<PositionReference> findPosition(UUID companyId, UUID positionId) {
        return read.execute(tx -> { company(companyId); return store.position(companyId, positionId).map(OrganizationService::reference); });
    }
    public PositionReference registerPosition(String code, String name) {
        String canonical = OrganizationValues.code(code), display = OrganizationValues.name(name);
        return change(() -> {
            var value = new Position(currentCompany().id(), canonical, display, Instant.now());
            store.add(value); store.flush(); return reference(value);
        });
    }
    public PositionReference renamePosition(UUID companyId, UUID positionId, String name, Long expectedVersion) {
        if (expectedVersion == null) throw new OrganizationFailure(INVALID_VERSION);
        OrganizationValues.expectedVersion(expectedVersion);
        String display = OrganizationValues.name(name);
        return change(() -> {
            company(companyId);
            var value = store.position(companyId, positionId).orElseThrow(() -> new OrganizationFailure(POSITION_NOT_FOUND));
            value.rename(display, expectedVersion, Instant.now());
            store.verifyAtCommit(value); store.flush(); return reference(value);
        });
    }
    public PositionPage listPositions(UUID companyId, String status, int pageIndex, Integer pageSize) {
        int size = pageSize == null ? 20 : pageSize;
        if (pageIndex < 0 || size < 1 || size > 100 || (long) pageIndex * size > Integer.MAX_VALUE
                || (status != null && !status.equals("ACTIVE") && !status.equals("INACTIVE")))
            throw new OrganizationFailure(INVALID_PAGE);
        return read.execute(tx -> {
            company(companyId);
            var rows = store.positions(companyId, status, pageIndex * size, size + 1);
            var items = rows.stream().limit(size).map(value -> new PositionListItem(value.id(), value.companyId(),
                    value.code(), value.name(), value.status(), value.version())).toList();
            return new PositionPage(items, pageIndex, size, rows.size() > size);
        });
    }
    private <T> T change(Supplier<T> command) {
        try { return write.execute(tx -> command.get()); }
        catch (RuntimeException failure) {
            // Includes provider failures raised during commit, especially an unchanged-name OPTIMISTIC check.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof OptimisticLockException || cause instanceof OptimisticLockingFailureException)
                    throw new OrganizationFailure(VERSION_CONFLICT, failure);
            }
            throw failure;
        }
    }
}
