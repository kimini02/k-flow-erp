package com.kflow.erp.iam.application;
import java.util.UUID;
public record RevokeUserRole(UUID companyId,UUID grantId,Long expectedGrantVersion,String reason) {}
