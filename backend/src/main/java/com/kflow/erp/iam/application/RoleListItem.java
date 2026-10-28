package com.kflow.erp.iam.application;
import java.util.UUID;
public record RoleListItem(UUID roleId,String code,String name,String status,long version) {}
