package com.kflow.erp.iam.application;
import java.util.UUID;
public record UserListItem(UUID userId,UUID companyId,UUID employeeId,String loginKey,String accountStatus,long version){}
