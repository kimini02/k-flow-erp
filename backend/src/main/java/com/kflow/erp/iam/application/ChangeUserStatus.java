package com.kflow.erp.iam.application;
import java.util.UUID;
public record ChangeUserStatus(UUID companyId,UUID userId,Long expectedUserVersion,String reason){}
