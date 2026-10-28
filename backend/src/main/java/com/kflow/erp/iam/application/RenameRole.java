package com.kflow.erp.iam.application;
import java.util.UUID;
public record RenameRole(UUID companyId,UUID roleId,String name,Long expectedRoleVersion,String reason) {}
