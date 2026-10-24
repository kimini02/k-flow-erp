package com.kflow.erp.organization.application;
import java.util.List;
public record DepartmentPage(List<DepartmentListItem> items, int pageIndex, int pageSize, boolean hasNext) {
    public DepartmentPage { items = List.copyOf(items); }
}
