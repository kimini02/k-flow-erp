package com.kflow.erp.hr.application;
import java.time.LocalDate;
import java.util.UUID;
/** HR-01 only: status is always EMPLOYED; no arbitrary lifecycle or history write inputs. */
public record CreateEmployee(UUID companyId, String employeeNo, String displayName, LocalDate joinedOn,
        UUID siteId, UUID departmentId, UUID positionId, String reason) {}
