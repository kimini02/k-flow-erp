package com.kflow.erp.iam.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity(name="IamRole") @Table(name="iam_role")
public class Role {
    @Id private UUID id;
    @Column(nullable=false,updatable=false) private UUID companyId;
    @Column(nullable=false,updatable=false,length=32) private String code;
    @Column(nullable=false,length=100) private String name;
    @Column(nullable=false,length=16) private String status;
    @Version @Column(nullable=false) private Long version;
    @Column(nullable=false,updatable=false,length=500) private String creationReason;
    @Column(length=500) private String lastChangeReason;
    @Column(length=16) private String lastChangeKind;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    protected Role() {}
    public Role(UUID company,String code,String name,String reason,Instant now) {
        id=UUID.randomUUID();companyId=IamValues.reference(company);this.code=RoleValues.code(code);
        this.name=RoleValues.name(name);creationReason=IamValues.reason(reason);status="INACTIVE";
        createdAt=Objects.requireNonNull(now);updatedAt=now;
    }
    public void rename(String value,String reason,Instant now) {
        String next=RoleValues.name(value),why=IamValues.reason(reason);Objects.requireNonNull(now);
        if(name.equals(next))return;
        name=next;changed("RENAME",why,now);
    }
    public void changeStatus(String value,String reason,Instant now) {
        String next=Objects.requireNonNull(RoleValues.status(value)),why=IamValues.reason(reason);Objects.requireNonNull(now);
        if(status.equals(next))return;
        status=next;changed(next.equals("ACTIVE")?"ACTIVATE":"DEACTIVATE",why,now);
    }
    private void changed(String kind,String reason,Instant now) {lastChangeKind=kind;lastChangeReason=reason;updatedAt=now;}
    public record Facts(UUID id,UUID companyId,String code,String name,String status,long version,String creationReason,
                        String lastChangeReason,String lastChangeKind,Instant createdAt,Instant updatedAt) {}
    public Facts facts(){return new Facts(id,companyId,code,name,status,version,creationReason,lastChangeReason,lastChangeKind,createdAt,updatedAt);}
}
