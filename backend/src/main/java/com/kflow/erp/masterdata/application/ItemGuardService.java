package com.kflow.erp.masterdata.application;

import com.kflow.erp.masterdata.api.*;
import com.kflow.erp.masterdata.domain.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static com.kflow.erp.masterdata.api.ItemFailure.Code.*;

@Service
public class ItemGuardService implements ItemBusinessUseGuard, ItemFulfillmentGuard, ItemReferenceGuard {
    private final ItemStore store;
    private final Clock clock;
    private final TransactionTemplate participate;
    public ItemGuardService(ItemStore store, PlatformTransactionManager manager,
            @Qualifier("itemClock") Clock clock) {
        this.store = store; this.clock = clock;
        participate = new TransactionTemplate(manager);
        participate.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }
    @Override public List<ItemReference> lockForNewUse(UUID company, List<ObservedItem> observations, Source source) {
        return inRoot(() -> {
            company(company); validate(observations);
            if (source == null || source.kind() == null) throw new ItemFailure(INVALID_INPUT);
            ItemValues.reference(source.sourceId());
            var items = store.lock(company, observations.stream().map(ObservedItem::itemId).toList(), ItemStore.Lock.UPDATE);
            observe(items, observations);
            var result = new ArrayList<ItemReference>(); var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            for (var before : items) {
                var after = before.firstUse(source, now);
                if (after != before) store.update(before, after);
                result.add(after.reference());
            }
            return List.copyOf(result);
        });
    }
    @Override public List<ItemReference> lockForExistingPoReceipt(UUID company, List<ObservedItem> observations) {
        return inRoot(() -> {
            company(company); validate(observations);
            var items = store.lock(company, observations.stream().map(ObservedItem::itemId).toList(), ItemStore.Lock.UPDATE);
            observe(items, observations);
            for (var item : items) {
                item.requirePurchasable();
                if (!item.used()) throw new ItemFailure(ITEM_NOT_USED);
            }
            return items.stream().map(Item::reference).toList();
        });
    }
    @Override public List<ItemReference> protectForDraft(UUID company, Collection<UUID> ids) {
        return inRoot(() -> {
            company(company);
            if (ids == null || ids.isEmpty()) throw new ItemFailure(INVALID_INPUT);
            ids.forEach(ItemValues::reference);
            return store.lock(company, ids, ItemStore.Lock.KEY_SHARE).stream().map(Item::reference).toList();
        });
    }
    private <T> T inRoot(Supplier<T> action) {
        try { return participate.execute(tx -> { store.requireWritableTransaction(); return action.get(); }); }
        catch (IllegalTransactionStateException failure) { throw new ItemFailure(TRANSACTION_BOUNDARY_VIOLATION, failure); }
    }
    private void company(UUID id) {
        ItemValues.reference(id);
    }
    private static void validate(List<ObservedItem> observations) {
        if (observations == null || observations.isEmpty()) throw new ItemFailure(INVALID_INPUT);
        for (var observed : observations) {
            if (observed == null || observed.baseUom() == null || observed.usageProfile() == null || observed.stockManaged() == null)
                throw new ItemFailure(INVALID_INPUT);
            ItemValues.reference(observed.itemId());
        }
    }
    private static void observe(List<Item> items, List<ObservedItem> observations) {
        var byId = new HashMap<UUID, Item>(); items.forEach(i -> byId.put(i.id(), i));
        // Validate every occurrence: contradictory lines of the same Item must not be silently deduplicated.
        observations.forEach(observed -> byId.get(observed.itemId()).observe(observed));
    }
}
