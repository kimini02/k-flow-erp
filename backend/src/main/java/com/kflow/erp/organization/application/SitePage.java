package com.kflow.erp.organization.application;
import java.util.List;
public record SitePage(List<SiteListItem> items, int pageIndex, int pageSize, boolean hasNext) {
    public SitePage { items = List.copyOf(items); }
}
