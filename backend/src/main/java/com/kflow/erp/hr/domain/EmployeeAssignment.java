package com.kflow.erp.hr.domain;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name="hr_employee_assignment")
public class EmployeeAssignment {
    @Id @Column(updatable=false) private UUID id;
    @Column(nullable=false,updatable=false) private UUID companyId;
    @Column(nullable=false,updatable=false) private UUID employeeId;
    @Column(nullable=false,updatable=false) private UUID siteId;
    @Column(nullable=false,updatable=false) private UUID departmentId;
    @Column(nullable=false,updatable=false) private UUID positionId;
    @Column(nullable=false,updatable=false) private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    @Column(nullable=false,updatable=false,length=500) private String reason;
    @Version @Column(nullable=false) private Long version;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    protected EmployeeAssignment() {}
    public EmployeeAssignment(UUID company, UUID employee, UUID site, UUID department, UUID position,
                              LocalDate from, String reason, Instant now) {
        id=UUID.randomUUID(); companyId=HrValues.reference(company); employeeId=HrValues.reference(employee);
        siteId=HrValues.reference(site); departmentId=HrValues.reference(department); positionId=HrValues.reference(position);
        effectiveFrom=HrValues.date(from); this.reason=HrValues.reason(reason); createdAt=now; updatedAt=now;
    }
    public UUID id() { return id; }
    public long version() { return version; }
}
