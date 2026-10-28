package com.kflow.erp.iam.application;
import com.kflow.erp.iam.domain.UserRole;
import java.time.Instant;
import java.util.UUID;
/** Stored grant facts, never an access decision or a permission token. */
public record UserRoleView(UUID grantId,UUID companyId,UUID userId,UUID roleId,Instant grantedAt,String grantReason,
        Instant revokedAt,String revokeReason,String revokeKind,long version,String state) {
    public static UserRoleView of(UserRole value){var f=value.facts();return new UserRoleView(f.grantId(),f.companyId(),f.userId(),f.roleId(),f.grantedAt(),f.grantReason(),f.revokedAt(),f.revokeReason(),f.revokeKind(),f.version(),f.state());}
}
