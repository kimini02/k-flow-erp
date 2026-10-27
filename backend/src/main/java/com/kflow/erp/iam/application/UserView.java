package com.kflow.erp.iam.application;
import com.kflow.erp.iam.domain.User;
import java.time.Instant;
import java.util.UUID;
/** IAM-internal detail; not an authentication/authorization result or a Published query. */
public record UserView(UUID userId,UUID companyId,UUID employeeId,String loginKey,String accountStatus,long version,
        String creationReason,String lastStatusReason,Instant statusChangedAt,Instant createdAt,Instant updatedAt){
    public static UserView of(User user){var f=user.facts();return new UserView(f.id(),f.companyId(),f.employeeId(),f.loginKey(),f.accountStatus(),f.version(),f.creationReason(),f.lastStatusReason(),f.statusChangedAt(),f.createdAt(),f.updatedAt());}
}
