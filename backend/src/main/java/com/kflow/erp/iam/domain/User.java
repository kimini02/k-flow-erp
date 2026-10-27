package com.kflow.erp.iam.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity(name="IamUser") @Table(name="iam_user")
public class User {
    @Id private UUID id;
    @Column(nullable=false,updatable=false) private UUID companyId;
    @Column(nullable=false,updatable=false) private UUID employeeId;
    @Column(nullable=false,updatable=false,length=64) private String loginKey;
    @Column(nullable=false,length=16) private String accountStatus;
    @Version @Column(nullable=false) private Long version;
    @Column(nullable=false) private long authRevocationGeneration;
    @Column(nullable=false,updatable=false,length=500) private String creationReason;
    @Column(length=500) private String lastStatusReason;
    private Instant statusChangedAt;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    protected User(){}
    public User(UUID company,UUID employee,String key,String reason,Instant now){
        id=UUID.randomUUID();companyId=IamValues.reference(company);employeeId=IamValues.reference(employee);
        loginKey=IamValues.loginKey(key);creationReason=IamValues.reason(reason);
        accountStatus="DISABLED";createdAt=java.util.Objects.requireNonNull(now);updatedAt=now;
    }
    public void changeStatus(String target,String reason,Instant now){
        if(target==null)throw new IllegalArgumentException("target required");IamValues.status(target);
        String normalized=IamValues.reason(reason);java.util.Objects.requireNonNull(now);
        if(accountStatus.equals(target))return;
        long next="DISABLED".equals(target)?Math.incrementExact(authRevocationGeneration):authRevocationGeneration;
        accountStatus=target;lastStatusReason=normalized;statusChangedAt=now;updatedAt=now;authRevocationGeneration=next;
    }
    public record Facts(UUID id,UUID companyId,UUID employeeId,String loginKey,String accountStatus,long version,
            String creationReason,String lastStatusReason,Instant statusChangedAt,Instant createdAt,Instant updatedAt){}
    public Facts facts(){return new Facts(id,companyId,employeeId,loginKey,accountStatus,version,creationReason,lastStatusReason,statusChangedAt,createdAt,updatedAt);}
}
