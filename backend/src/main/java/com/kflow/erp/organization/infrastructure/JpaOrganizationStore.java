package com.kflow.erp.organization.infrastructure;

import com.kflow.erp.organization.application.OrganizationStore;
import com.kflow.erp.organization.domain.Company;
import com.kflow.erp.organization.domain.Site;
import com.kflow.erp.organization.domain.Department;
import com.kflow.erp.organization.domain.Position;
import com.kflow.erp.organization.api.OrganizationFailure;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static com.kflow.erp.organization.api.OrganizationFailure.Code.*;

@Repository
public class JpaOrganizationStore implements OrganizationStore {
    private final EntityManager em;
    public JpaOrganizationStore(EntityManager em) { this.em = em; }
    public List<Company> companies() {
        // Two rows suffice to detect a damaged singleton without COUNT or silently choosing the first.
        return em.createQuery("from Company", Company.class).setMaxResults(2).getResultList();
    }
    public Optional<Site> site(UUID companyId, UUID siteId) {
        return em.createQuery("from Site where companyId = :company and id = :site", Site.class)
                .setParameter("company", companyId).setParameter("site", siteId)
                .getResultList().stream().findFirst();
    }
    public List<Site> sites(UUID companyId, String status, int offset, int limit) {
        var query = em.createQuery("from Site where companyId = :company"
                + (status == null ? "" : " and status = :status") + " order by code asc, id asc", Site.class)
                .setParameter("company", companyId).setFirstResult(offset).setMaxResults(limit);
        if (status != null) query.setParameter("status", status);
        return query.getResultList();
    }
    public void add(Site site) { em.persist(site); }
    public void verifyAtCommit(Company company) { em.lock(company, LockModeType.OPTIMISTIC); }
    public void verifyAtCommit(Site site) { em.lock(site, LockModeType.OPTIMISTIC); }

    public Optional<Department> department(UUID companyId, UUID departmentId) {
        return em.createQuery("from Department where companyId = :company and id = :id", Department.class)
                .setParameter("company", companyId).setParameter("id", departmentId)
                .getResultList().stream().findFirst();
    }
    public List<Department> departments(UUID companyId, String status, int offset, int limit) {
        var query = em.createQuery("from Department where companyId = :company"
                + (status == null ? "" : " and status = :status") + " order by code asc, id asc", Department.class)
                .setParameter("company", companyId).setFirstResult(offset).setMaxResults(limit);
        if (status != null) query.setParameter("status", status);
        return query.getResultList();
    }
    public void add(Department value) { em.persist(value); }
    public void verifyAtCommit(Department value) { em.lock(value, LockModeType.OPTIMISTIC); }

    public Optional<Position> position(UUID companyId, UUID positionId) {
        return em.createQuery("from Position where companyId = :company and id = :id", Position.class)
                .setParameter("company", companyId).setParameter("id", positionId)
                .getResultList().stream().findFirst();
    }
    public List<Position> positions(UUID companyId, String status, int offset, int limit) {
        var query = em.createQuery("from Position where companyId = :company"
                + (status == null ? "" : " and status = :status") + " order by code asc, id asc", Position.class)
                .setParameter("company", companyId).setFirstResult(offset).setMaxResults(limit);
        if (status != null) query.setParameter("status", status);
        return query.getResultList();
    }
    public void add(Position value) { em.persist(value); }
    public void verifyAtCommit(Position value) { em.lock(value, LockModeType.OPTIMISTIC); }
    public void flush() {
        try { em.flush(); }
        catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint) {
                    var code = switch (constraint.getConstraintName() == null ? "" : constraint.getConstraintName()) {
                        case "uq_organization_site_company_code" -> SITE_CODE_CONFLICT;
                        case "uq_organization_department_company_code" -> DEPARTMENT_CODE_CONFLICT;
                        case "uq_organization_position_company_code" -> POSITION_CODE_CONFLICT;
                        default -> null;
                    };
                    if (code != null) throw new OrganizationFailure(code, failure);
                }
            }
            throw failure; // FK/connection/programming failures are not duplicate-code conflicts.
        }
    }
}
