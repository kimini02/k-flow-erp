package com.kflow.erp.masterdata.infrastructure;

import com.kflow.erp.masterdata.api.*;
import com.kflow.erp.masterdata.application.*;
import com.kflow.erp.masterdata.domain.Item;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import static com.kflow.erp.masterdata.api.ItemFailure.Code.*;

/** Owner-only SQL on Hibernate's existing physical connection; no managed state, auto-flush or clear. */
@Repository
public class JdbcItemStore implements ItemStore {
    private static final String COLUMNS = "id,company_id,code,name,specification,category,usage_profile,base_uom,"
            + "stock_managed,reference_price,status,version,first_used_at,first_use_kind,first_use_source_id,created_at,updated_at";
    private final EntityManager em;
    private final EntityManagerFactory factory;
    private final int lockTimeoutMs;
    public JdbcItemStore(EntityManager em, EntityManagerFactory factory,
            @Value("${kflow.masterdata.lock-timeout-ms:5000}") int lockTimeoutMs) {
        if (lockTimeoutMs < 1) throw new IllegalArgumentException("Item lock timeout must be positive");
        this.em = em; this.factory = factory; this.lockTimeoutMs = lockTimeoutMs;
    }
    @Override public void requireWritableTransaction() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        Object resource = TransactionSynchronizationManager.getResource(factory);
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || isolation != null && isolation != Connection.TRANSACTION_READ_COMMITTED
                || !(resource instanceof EntityManagerHolder holder)
                || !holder.getEntityManager().isJoinedToTransaction() || !em.isJoinedToTransaction())
            throw new ItemFailure(TRANSACTION_BOUNDARY_VIOLATION);
        em.unwrap(Session.class).doWork(c -> {
            if (c.getAutoCommit() || c.isReadOnly() || c.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED)
                throw new ItemFailure(TRANSACTION_BOUNDARY_VIOLATION);
            try (var s = c.createStatement(); var r = s.executeQuery(
                    "select current_setting('transaction_isolation'),current_setting('transaction_read_only')")) {
                if (!r.next() || !"read committed".equals(r.getString(1)) || !"off".equals(r.getString(2)))
                    throw new ItemFailure(TRANSACTION_BOUNDARY_VIOLATION);
            }
        });
    }
    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException; }
    private <T> T bounded(Work<T> work) {
        return em.unwrap(Session.class).doReturningWork(c -> {
            String previous;
            try (var s = c.createStatement(); var r = s.executeQuery("show lock_timeout")) { r.next(); previous = r.getString(1); }
            boolean supply = "0".equals(previous);
            if (supply) timeout(c, lockTimeoutMs + "ms");
            T result = work.run(c);
            if (supply) timeout(c, previous);
            return result; // On a failed SQL statement the root aborts: no restoring SQL in an aborted TX.
        });
    }
    private static void timeout(Connection c, String value) throws SQLException {
        try (var s = c.prepareStatement("select set_config('lock_timeout',?,true)")) { s.setString(1, value); s.execute(); }
    }
    @Override public List<Item> lock(UUID company, Collection<UUID> input, Lock mode) {
        var ids = new LinkedHashSet<>(input);
        if (ids.isEmpty()) throw new ItemFailure(INVALID_INPUT);
        return bounded(c -> {
            String sql = "select " + COLUMNS + " from masterdata_item where company_id=? and id=any(?) order by id "
                    + (mode == Lock.UPDATE ? "FOR UPDATE" : "FOR KEY SHARE");
            var array = c.createArrayOf("uuid", ids.toArray());
            try (var s = c.prepareStatement(sql)) {
                s.setObject(1, company); s.setArray(2, array);
                var rows = new ArrayList<Item>();
                try (var r = s.executeQuery()) { while (r.next()) rows.add(item(r)); }
                if (rows.size() != ids.size()) throw new ItemFailure(ITEM_NOT_FOUND);
                return List.copyOf(rows);
            } finally { array.free(); }
        });
    }
    @Override public void insert(Item item) {
        bounded(c -> {
            try (var s = c.prepareStatement("insert into masterdata_item (" + COLUMNS + ") values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                bind(s, item.id(), item.companyId(), item.code(), item.name(), item.specification(), item.category(),
                        item.usageProfile(), item.baseUom().name(), item.stockManaged(), item.referencePrice(), item.status(),
                        item.version(), item.firstUsedAt(), item.firstUseKind(), item.firstUseSourceId(), item.createdAt(), item.updatedAt());
                s.executeUpdate(); return null;
            }
        });
    }
    @Override public void update(Item before, Item after) {
        if (!before.id().equals(after.id()) || !before.companyId().equals(after.companyId())
                || before.version() == Long.MAX_VALUE || after.version() != before.version() + 1)
            throw new IllegalArgumentException("Item update must preserve identity and increment once");
        bounded(c -> {
            try (var s = c.prepareStatement("update masterdata_item set code=?,name=?,specification=?,category=?,usage_profile=?,"
                    + "base_uom=?,stock_managed=?,reference_price=?,status=?,version=?,first_used_at=?,first_use_kind=?,"
                    + "first_use_source_id=?,updated_at=? where company_id=? and id=? and version=?")) {
                bind(s, after.code(), after.name(), after.specification(), after.category(), after.usageProfile(),
                        after.baseUom().name(), after.stockManaged(), after.referencePrice(), after.status(), after.version(),
                        after.firstUsedAt(), after.firstUseKind(), after.firstUseSourceId(), after.updatedAt(),
                        before.companyId(), before.id(), before.version());
                if (s.executeUpdate() != 1) throw new ItemFailure(VERSION_CONFLICT);
                return null;
            }
        });
    }
    @Override public Optional<Item> find(UUID company, UUID id) {
        return em.unwrap(Session.class).doReturningWork(c -> {
            try (var s = c.prepareStatement("select " + COLUMNS + " from masterdata_item where company_id=? and id=?")) {
                bind(s, company, id);
                try (var r = s.executeQuery()) { return r.next() ? Optional.of(item(r)) : Optional.empty(); }
            }
        });
    }
    @Override public ItemPage list(UUID company, String status, String search, ItemPage.Sort sort, int page, int size) {
        String order = switch (sort) { case CODE -> "code"; case NAME -> "name"; case UPDATED_AT -> "updated_at"; };
        // Count and even an empty/out-of-range page share one statement snapshot. Order is an enum, never raw input.
        String sql = "with filtered as (select " + COLUMNS + " from masterdata_item where company_id=?"
                + " and (?::varchar is null or status=?) and (strpos(lower(code),lower(?))>0 or strpos(lower(name),lower(?))>0)),"
                + " numbered as (select *,row_number() over(order by " + order + ",id) rn from filtered),"
                + " page_rows as (select * from numbered order by rn offset ? limit ?)"
                + " select totals.total,p.* from (select count(*) total from filtered) totals left join page_rows p on true order by p.rn";
        return em.unwrap(Session.class).doReturningWork(c -> {
            try (var s = c.prepareStatement(sql)) {
                bind(s, company, status, status, search, search, (long) page * size, size);
                long total = 0; var rows = new ArrayList<ItemView>();
                try (var r = s.executeQuery()) {
                    while (r.next()) { total = r.getLong("total"); if (r.getObject("id") != null) rows.add(ItemView.of(item(r))); }
                }
                return new ItemPage(rows, total, page, size);
            }
        });
    }
    private static void bind(PreparedStatement s, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof Instant instant) s.setTimestamp(i + 1, Timestamp.from(instant));
            else s.setObject(i + 1, values[i]);
        }
    }
    private static Instant instant(ResultSet r, String column) throws SQLException {
        var time = r.getObject(column, OffsetDateTime.class); return time == null ? null : time.toInstant();
    }
    private static Item item(ResultSet r) throws SQLException {
        return new Item(r.getObject("id", UUID.class), r.getObject("company_id", UUID.class), r.getString("code"),
                r.getString("name"), r.getString("specification"), r.getString("category"), r.getString("usage_profile"),
                Unit.valueOf(r.getString("base_uom")), r.getBoolean("stock_managed"),
                r.getBigDecimal("reference_price") == null ? null : r.getBigDecimal("reference_price").setScale(0),
                r.getString("status"), r.getLong("version"), instant(r, "first_used_at"), r.getString("first_use_kind"),
                r.getObject("first_use_source_id", UUID.class), instant(r, "created_at"), instant(r, "updated_at"));
    }
    @Override public RuntimeException translate(RuntimeException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException pg && "23505".equals(pg.getSQLState())
                    && pg.getServerErrorMessage() != null
                    && "uq_masterdata_item_company_code".equals(pg.getServerErrorMessage().getConstraint()))
                return new ItemFailure(ITEM_CODE_CONFLICT, failure);
        }
        return failure;
    }
}
