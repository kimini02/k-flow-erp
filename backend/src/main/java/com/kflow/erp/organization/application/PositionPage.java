package com.kflow.erp.organization.application;
import java.util.List;
public record PositionPage(List<PositionListItem> items, int pageIndex, int pageSize, boolean hasNext) {
    public PositionPage { items = List.copyOf(items); }
}
