package com.kflow.erp.masterdata.application;

import java.util.List;

/** Rows and total come from one SQL statement snapshot. Separate pages are not a frozen snapshot. */
public record ItemPage(List<ItemView> items, long total, int page, int size) {
    public ItemPage { items = List.copyOf(items); }
    public enum Sort { CODE, NAME, UPDATED_AT }
}
