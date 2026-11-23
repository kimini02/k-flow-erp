package com.kflow.erp.masterdata.application;

import com.kflow.erp.masterdata.domain.Item;
import java.util.*;

public interface ItemStore {
    enum Lock { UPDATE, KEY_SHARE }
    void requireWritableTransaction();
    List<Item> lock(UUID company, Collection<UUID> ids, Lock lock);
    void insert(Item item);
    void update(Item before, Item after);
    Optional<Item> find(UUID company, UUID id);
    ItemPage list(UUID company, String status, String search, ItemPage.Sort sort, int page, int size);
    RuntimeException translate(RuntimeException failure);
}
