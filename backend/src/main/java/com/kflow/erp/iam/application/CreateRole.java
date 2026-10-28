package com.kflow.erp.iam.application;
import java.util.UUID;
public record CreateRole(UUID companyId,String code,String name,String reason) {}
