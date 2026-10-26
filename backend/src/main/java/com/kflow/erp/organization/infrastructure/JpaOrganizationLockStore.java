package com.kflow.erp.organization.infrastructure;

import com.kflow.erp.organization.api.OrganizationFailure;
import com.kflow.erp.organization.application.OrganizationLockStore;
import com.kflow.erp.organization.application.OrganizationTarget;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import static com.kflow.erp.organization.api.OrganizationFailure.Code.*;

/** JDBC work on Hibernate's existing connection avoids stale entities and consumer auto-flush. */
@Repository
public class JpaOrganizationLockStore implements OrganizationLockStore {
    private final EntityManager em;
    private final EntityManagerFactory factory;
    private final int lockTimeoutMs;

    public JpaOrganizationLockStore(EntityManager em, EntityManagerFactory factory,
            @Value("${kflow.organization.lock-timeout-ms:5000}") int lockTimeoutMs) {
        if (lockTimeoutMs < 1) throw new IllegalArgumentException("Organization lock timeout must be positive");
        this.em = em;
        this.factory = factory;
        this.lockTimeoutMs = lockTimeoutMs;
    }
    public void requireWritableTransaction() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        Object resource = TransactionSynchronizationManager.getResource(factory);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || (isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED)
                || !(resource instanceof EntityManagerHolder holder)
                || !holder.getEntityManager().isJoinedToTransaction() || !em.isJoinedToTransaction())
            throw new OrganizationFailure(TRANSACTION_BOUNDARY_VIOLATION);
        em.unwrap(Session.class).doWork(connection -> {
            if (connection.getAutoCommit() || connection.isReadOnly()
                    || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
                throw new OrganizationFailure(TRANSACTION_BOUNDARY_VIOLATION);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "select current_setting('transaction_isolation'), current_setting('transaction_read_only')")) {
                if (!rows.next() || !"read committed".equals(rows.getString(1)) || !"off".equals(rows.getString(2)))
                    throw new OrganizationFailure(TRANSACTION_BOUNDARY_VIOLATION);
            }
        });
    }
    public void requireCompany(UUID id) {
        em.unwrap(Session.class).doWork(connection -> {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("select id from organization_company limit 2")) {
                if (!rows.next()) throw new OrganizationFailure(COMPANY_CONFIGURATION_INVALID);
                UUID current = rows.getObject(1, UUID.class);
                if (rows.next()) throw new OrganizationFailure(COMPANY_CONFIGURATION_INVALID);
                if (!current.equals(id)) throw new OrganizationFailure(COMPANY_NOT_FOUND);
            }
        });
    }
    public String lockForAssignment(OrganizationTarget kind, UUID company, UUID id) {
        return lock(kind, company, id, "FOR SHARE");
    }
    public void lockForState(OrganizationTarget kind, UUID company, UUID id) {
        lock(kind, company, id, "FOR UPDATE");
    }
    private String lock(OrganizationTarget kind, UUID company, UUID id, String mode) {
        String table = switch (kind) {
            case SITE -> "organization_site";
            case DEPARTMENT -> "organization_department";
            case POSITION -> "organization_position";
        };
        return em.unwrap(Session.class).doReturningWork(connection -> {
            String previous;
            try (var statement = connection.createStatement(); var row = statement.executeQuery("show lock_timeout")) {
                row.next(); previous = row.getString(1);
            }
            // Preserve an existing bounded consumer timeout; supply a bounded default only for zero.
            boolean configured = "0".equals(previous);
            if (configured) timeout(connection, lockTimeoutMs + "ms");
            try (var statement = connection.prepareStatement(
                    "select status from " + table + " where company_id = ? and id = ? " + mode)) {
                statement.setObject(1, company); statement.setObject(2, id);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new OrganizationFailure(kind.missing);
                    String status = rows.getString(1);
                    if (configured) timeout(connection, previous);
                    return status;
                }
            }
            // On SQL failure the root must abort; do not issue restoring SQL in an aborted transaction.
        });
    }
    private static void timeout(Connection connection, String value) throws SQLException {
        try (var statement = connection.prepareStatement("select set_config('lock_timeout', ?, true)")) {
            statement.setString(1, value); statement.execute();
        }
    }
}
