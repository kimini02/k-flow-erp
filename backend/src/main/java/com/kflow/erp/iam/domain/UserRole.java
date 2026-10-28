package com.kflow.erp.iam.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One grant intent and its permanent revocation history. No entity associations or access policy. */
@Entity(name="IamUserRole") @Table(name="iam_user_role")
public class UserRole {
    @Id private UUID id;
    @Column(nullable=false,updatable=false) private UUID companyId;
    @Column(nullable=false,updatable=false) private UUID userId;
    @Column(nullable=false,updatable=false) private UUID roleId;
    @Column(nullable=false,updatable=false) private Instant grantedAt;
    @Column(nullable=false,updatable=false,length=500) private String grantReason;
    private Instant revokedAt;
    @Column(length=500) private String revokeReason;
    @Column(length=32) private String revokeKind;
    @Version @Column(nullable=false) private Long version;
    protected UserRole() {}
    public UserRole(UUID company,UUID grant,UUID user,UUID role,String reason,Instant now){
        id=UserRoleValues.grantId(grant);companyId=IamValues.reference(company);userId=IamValues.reference(user);roleId=IamValues.reference(role);
        grantReason=IamValues.reason(reason);grantedAt=Objects.requireNonNull(now);
        // Keep boxed @Version null while transient; persist seeds 0. Assigned IDs never use merge/upsert.
    }
    public void revoke(String kind,String reason,Instant now){
        String why=IamValues.reason(reason);Objects.requireNonNull(now);
        if(!java.util.Set.of("MANUAL","ROLE_DEACTIVATION").contains(kind))throw new IllegalArgumentException("Unsupported revocation kind");
        if(revokedAt!=null)return;
        revokedAt=now;revokeReason=why;revokeKind=kind;
    }
    public record Facts(UUID grantId,UUID companyId,UUID userId,UUID roleId,Instant grantedAt,String grantReason,
                        Instant revokedAt,String revokeReason,String revokeKind,Long version,String state) {}
    public Facts facts(){return new Facts(id,companyId,userId,roleId,grantedAt,grantReason,revokedAt,revokeReason,revokeKind,version,revokedAt==null?"UNREVOKED":"REVOKED");}
}
