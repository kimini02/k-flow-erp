package com.kflow.erp.masterdata.application;

import com.kflow.erp.masterdata.api.*;
import com.kflow.erp.masterdata.domain.*;
import com.kflow.erp.organization.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.Clock;
import java.util.*;
import java.util.function.UnaryOperator;
import static com.kflow.erp.masterdata.api.ItemFailure.Code.*;

@Service
public class ItemService implements ItemReferenceQueries {
    private final ItemStore store;
    private final OrganizationReferenceQueries organization;
    private final Clock clock;
    private final TransactionTemplate root, read;
    public ItemService(ItemStore store, OrganizationReferenceQueries organization, PlatformTransactionManager manager,
            @Qualifier("itemClock") Clock clock) {
        this.store = store; this.organization = organization; this.clock = clock;
        root = new TransactionTemplate(manager); root.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read = new TransactionTemplate(manager); read.setReadOnly(true);
    }
    public ItemView create(ItemCommands.Create input) {
        input(input); standalone();
        try { return root.execute(tx -> {
            store.requireWritableTransaction();
            var item = Item.create(organization.getCompany().companyId(), input.code(), input.name(),
                    input.specification(), input.category(), input.usageProfile(), input.baseUom(),
                    input.stockManaged(), input.referencePrice(), clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            store.insert(item); return ItemView.of(item);
        }); } catch (RuntimeException failure) { throw store.translate(failure); }
    }
    public ItemView describe(ItemCommands.Describe input) {
        input(input); return change(input.companyId(), input.itemId(), input.expectedVersion(),
                item -> item.describe(input.name(), input.specification(), clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
    }
    public ItemView changeAttributes(ItemCommands.Attributes input) {
        input(input); return change(input.companyId(), input.itemId(), input.expectedVersion(),
                item -> item.attributes(input.code(), input.category(), input.usageProfile(), input.baseUom(),
                        input.stockManaged(), input.explicitNewUomPrice(), clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
    }
    public ItemView changePrice(ItemCommands.Price input) {
        input(input); return change(input.companyId(), input.itemId(), input.expectedVersion(),
                item -> item.price(input.observedBaseUom(), input.referencePrice(), clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
    }
    public ItemView changeStatus(ItemCommands.State input) {
        input(input); return change(input.companyId(), input.itemId(), input.expectedVersion(),
                item -> item.state(input.status(), clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)));
    }
    private ItemView change(UUID company, UUID id, Long expected, UnaryOperator<Item> mutation) {
        ItemValues.reference(company); ItemValues.reference(id); long version = ItemValues.version(expected); standalone();
        try { return root.execute(tx -> {
            store.requireWritableTransaction(); company(company);
            var before = store.lock(company, List.of(id), ItemStore.Lock.UPDATE).getFirst();
            before.expect(version); var after = mutation.apply(before);
            if (after != before) store.update(before, after);
            return ItemView.of(after);
        }); } catch (RuntimeException failure) { throw store.translate(failure); }
    }
    public Optional<ItemView> find(UUID company, UUID id) {
        ItemValues.reference(company); ItemValues.reference(id);
        return read.execute(tx -> { company(company); return store.find(company, id).map(ItemView::of); });
    }
    @Override public Optional<ItemReference> findItem(UUID company, UUID id) {
        ItemValues.reference(company); ItemValues.reference(id);
        return read.execute(tx -> store.find(company, id).map(Item::reference));
    }
    public ItemPage list(UUID company, String status, String search, ItemPage.Sort sort, int page, int size) {
        ItemValues.reference(company); if (status != null) ItemValues.status(status);
        if (sort == null || page < 0 || size < 1 || size > 100) throw new ItemFailure(INVALID_INPUT);
        String query = search == null || search.isEmpty() ? "" : ItemValues.text(search, 100, false);
        return read.execute(tx -> { company(company); return store.list(company, status, query, sort, page, size); });
    }
    private void company(UUID id) {
        if (!organization.getCompany().companyId().equals(id))
            throw new OrganizationFailure(OrganizationFailure.Code.COMPANY_NOT_FOUND);
    }
    private static void standalone() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new ItemFailure(TRANSACTION_BOUNDARY_VIOLATION);
    }
    private static void input(Object input) { if (input == null) throw new ItemFailure(INVALID_INPUT); }
}
