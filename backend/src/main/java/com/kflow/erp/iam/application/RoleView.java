package com.kflow.erp.iam.application;
import com.kflow.erp.iam.domain.Role;
import java.time.Instant;
import java.util.UUID;
/** Internal stored facts, not a grant or an access decision. */
public record RoleView(UUID roleId,UUID companyId,String code,String name,String status,long version,String creationReason,
        String lastChangeReason,String lastChangeKind,Instant createdAt,Instant updatedAt) {
    public static RoleView of(Role role){var f=role.facts();return new RoleView(f.id(),f.companyId(),f.code(),f.name(),f.status(),f.version(),f.creationReason(),f.lastChangeReason(),f.lastChangeKind(),f.createdAt(),f.updatedAt());}
}
