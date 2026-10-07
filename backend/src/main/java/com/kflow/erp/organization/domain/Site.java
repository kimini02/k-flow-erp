package com.kflow.erp.organization.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import com.kflow.erp.organization.api.OrganizationFailure;
import static com.kflow.erp.organization.api.OrganizationFailure.Code.VERSION_CONFLICT;

@Entity
@Table(name = "organization_site")
public class Site {
    @Id @Column(updatable = false) private UUID id;
    @Column(nullable = false, updatable = false) private UUID companyId;
    @Column(nullable = false, length = 16) private String status;
    @Column(nullable = false, updatable = false, length = 32) private String code;
    @Column(nullable = false, length = 100) private String name;
    @Version @Column(nullable = false) private Long version;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    protected Site() {}
    public Site(UUID companyId, String code, String name, Instant now) {
        this.id = UUID.randomUUID(); this.companyId = companyId;
        this.code = code; this.name = name; this.status = "ACTIVE";
        this.createdAt = now; this.updatedAt = now;
    }
    public void rename(String name, long expectedVersion, Instant now) {
        if (version != expectedVersion) throw new OrganizationFailure(VERSION_CONFLICT);
        if (!this.name.equals(name)) { this.name = name; this.updatedAt = now; }
    }
    public UUID id() { return id; }
    public UUID companyId() { return companyId; }
    public String status() { return status; }
    public String code() { return code; }
    public String name() { return name; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
}
