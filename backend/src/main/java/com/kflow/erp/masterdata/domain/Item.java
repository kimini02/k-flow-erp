package com.kflow.erp.masterdata.domain;

import com.kflow.erp.masterdata.api.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import static com.kflow.erp.masterdata.api.ItemFailure.Code.*;

/** Immutable aggregate; the owner adapter persists a version-checked update without JPA auto-flush. */
public record Item(UUID id, UUID companyId, String code, String name, String specification,
        String category, String usageProfile, Unit baseUom, boolean stockManaged, BigDecimal referencePrice,
        String status, long version, Instant firstUsedAt, String firstUseKind, UUID firstUseSourceId,
        Instant createdAt, Instant updatedAt) {
    public static Item create(UUID company, String code, String name, String specification,
            String category, String profile, Unit unit, Boolean stock, BigDecimal price, Instant now) {
        ItemValues.profile(category, profile, stock);
        return new Item(UUID.randomUUID(), ItemValues.reference(company), ItemValues.code(code),
                ItemValues.name(name), ItemValues.specification(specification), category, profile,
                ItemValues.unit(unit), stock, ItemValues.price(price), "ACTIVE", 0, null, null, null, now, now);
    }
    public boolean used() { return firstUsedAt != null; }
    public void expect(long expected) {
        if (version != expected) throw new ItemFailure(VERSION_CONFLICT);
    }
    private Item changed(String code, String name, String spec, String category, String profile,
            Unit unit, boolean stock, BigDecimal price, String state, Instant usedAt, String kind,
            UUID source, Instant now) {
        if (version == Long.MAX_VALUE) throw new ItemFailure(VERSION_EXHAUSTED);
        return new Item(id, companyId, code, name, spec, category, profile, unit, stock, price, state,
                version + 1, usedAt, kind, source, createdAt, now);
    }
    public Item describe(String newName, String newSpecification, Instant now) {
        var n = ItemValues.name(newName); var s = ItemValues.specification(newSpecification);
        if (name.equals(n) && Objects.equals(specification, s)) return this;
        return changed(code, n, s, category, usageProfile, baseUom, stockManaged, referencePrice,
                status, firstUsedAt, firstUseKind, firstUseSourceId, now);
    }
    public Item attributes(String newCode, String newCategory, String newProfile, Unit newUom,
            Boolean newStock, BigDecimal explicitNewUomPrice, Instant now) {
        var c = ItemValues.code(newCode); ItemValues.profile(newCategory, newProfile, newStock);
        ItemValues.unit(newUom); var p = ItemValues.price(explicitNewUomPrice);
        // This field belongs only to a real UOM change; same-UOM price editing uses its own command.
        if (newUom == baseUom && explicitNewUomPrice != null) throw new ItemFailure(INVALID_INPUT);
        boolean change = !code.equals(c) || !category.equals(newCategory) || !usageProfile.equals(newProfile)
                || baseUom != newUom || stockManaged != newStock;
        if (!change) return this;
        if (used()) throw new ItemFailure(ITEM_ALREADY_USED);
        return changed(c, name, specification, newCategory, newProfile, newUom, newStock,
                baseUom == newUom ? referencePrice : p, status, firstUsedAt, firstUseKind, firstUseSourceId, now);
    }
    public Item price(Unit observedUnit, BigDecimal newPrice, Instant now) {
        if (ItemValues.unit(observedUnit) != baseUom) throw new ItemFailure(ITEM_FACTS_CHANGED);
        var p = ItemValues.price(newPrice);
        if (Objects.equals(referencePrice, p)) return this;
        return changed(code, name, specification, category, usageProfile, baseUom, stockManaged, p,
                status, firstUsedAt, firstUseKind, firstUseSourceId, now);
    }
    public Item state(String target, Instant now) {
        ItemValues.status(target);
        if (status.equals(target)) return this;
        return changed(code, name, specification, category, usageProfile, baseUom, stockManaged, referencePrice,
                target, firstUsedAt, firstUseKind, firstUseSourceId, now);
    }
    public void observe(ObservedItem observed) {
        if (!id.equals(observed.itemId()) || baseUom != observed.baseUom()
                || !usageProfile.equals(observed.usageProfile()) || observed.stockManaged() == null
                || stockManaged != observed.stockManaged()) throw new ItemFailure(ITEM_FACTS_CHANGED);
    }
    public void requirePurchasable() {
        if (!stockManaged || !("PURCHASED_MATERIAL".equals(usageProfile) || "RESALE".equals(usageProfile)))
            throw new ItemFailure(ITEM_NOT_PURCHASABLE);
    }
    public Item firstUse(ItemBusinessUseGuard.Source source, Instant now) {
        if (!"ACTIVE".equals(status)) throw new ItemFailure(ITEM_INACTIVE);
        if (source.kind() != ItemBusinessUseGuard.UseKind.STOCK_POSTING) requirePurchasable();
        if (used()) return this;
        return changed(code, name, specification, category, usageProfile, baseUom, stockManaged, referencePrice,
                status, now, source.kind().name(), source.sourceId(), now);
    }
    public ItemReference reference() {
        return new ItemReference(id, companyId, code, name, baseUom, usageProfile, stockManaged, status, version);
    }
}
