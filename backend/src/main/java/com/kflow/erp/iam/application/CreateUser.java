package com.kflow.erp.iam.application;
import java.util.UUID;
public record CreateUser(UUID companyId,UUID employeeId,String loginKey,String reason){}
