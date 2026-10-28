package com.kflow.erp.iam.application;
import java.util.List;
public record UserRolePage(List<UserRoleView> items,boolean hasNext){public UserRolePage{items=List.copyOf(items);}}
