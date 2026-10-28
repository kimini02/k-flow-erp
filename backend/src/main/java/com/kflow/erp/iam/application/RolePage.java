package com.kflow.erp.iam.application;
import java.util.List;
public record RolePage(List<RoleListItem> items,boolean hasNext){public RolePage{items=List.copyOf(items);}}
