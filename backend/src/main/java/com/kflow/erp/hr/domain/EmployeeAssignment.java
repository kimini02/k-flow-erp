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
    public UUID companyId() { return companyId; }
    public UUID employeeId() { return employeeId; }
    public UUID siteId() { return siteId; }
    public UUID departmentId() { return departmentId; }
    public UUID positionId() { return positionId; }
    public LocalDate effectiveFrom() { return effectiveFrom; }
    public LocalDate effectiveTo() { return effectiveTo; }
    public String reason() { return reason; }
    public boolean sameDestination(UUID site, UUID department, UUID position) {
        return siteId.equals(site) && departmentId.equals(department) && positionId.equals(position);
    }
    public void close(LocalDate date, Instant now) {
        HrValues.date(date);
        if (effectiveTo!=null || !effectiveFrom.isBefore(date))
            throw new com.kflow.erp.hr.api.HrFailure(com.kflow.erp.hr.api.HrFailure.Code.HR_ASSIGNMENT_INTEGRITY_VIOLATION);
        effectiveTo=date; updatedAt=java.util.Objects.requireNonNull(now);
    }
    public UUID id() { return id; }
    public long version() { return version; }
}
