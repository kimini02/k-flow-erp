package com.kflow.erp.hr.domain;

import com.kflow.erp.hr.api.EmployeeIdentity;
import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name="hr_employee")
public class Employee {
    @Id @Column(updatable=false) private UUID id;
    @Column(nullable=false,updatable=false) private UUID companyId;
    @Column(nullable=false,updatable=false,length=32) private String employeeNo;
    @Column(nullable=false,length=100) private String displayName;
    @Column(nullable=false,length=16) private String employmentStatus;
    @Column(nullable=false,updatable=false) private LocalDate joinedOn;
    private LocalDate terminationEffectiveOn;
    @Version @Column(nullable=false) private Long version;
    @Column(nullable=false,updatable=false) private Instant createdAt;
    @Column(nullable=false) private Instant updatedAt;
    protected Employee() {}
    public Employee(UUID company, String employeeNo, String name, LocalDate joined, Instant now) {
        id=UUID.randomUUID(); companyId=HrValues.reference(company);
        this.employeeNo=HrValues.employeeNo(employeeNo); displayName=HrValues.displayName(name);
        employmentStatus="EMPLOYED"; joinedOn=HrValues.date(joined); createdAt=now; updatedAt=now;
    }
    public UUID id() { return id; }
    public EmployeeIdentity identity() {
        return new EmployeeIdentity(id,companyId,employeeNo,displayName,employmentStatus,joinedOn,terminationEffectiveOn,version);
    }
}
