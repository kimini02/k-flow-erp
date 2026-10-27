package com.kflow.erp.iam.application;
import java.util.List;
public record UserPage(List<UserListItem> items,boolean hasNext){public UserPage{items=List.copyOf(items);}}
