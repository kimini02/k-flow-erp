package com.kflow.erp.iam.application;
import java.util.UUID;
public record ChangeRoleStatus(UUID companyId,UUID roleId,Long expectedRoleVersion,String reason) {}
