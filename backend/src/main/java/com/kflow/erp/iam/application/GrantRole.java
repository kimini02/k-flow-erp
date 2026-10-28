package com.kflow.erp.iam.application;
import java.util.UUID;
/** The caller retains the same grantId for retries of one grant intent. */
public record GrantRole(UUID companyId,UUID grantId,UUID userId,UUID roleId,String reason) {}
