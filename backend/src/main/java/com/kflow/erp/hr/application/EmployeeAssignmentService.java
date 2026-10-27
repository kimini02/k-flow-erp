package com.kflow.erp.hr.application;

import com.kflow.erp.hr.api.HrFailure;
import com.kflow.erp.hr.domain.*;
import com.kflow.erp.organization.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.List;
import static com.kflow.erp.hr.api.HrFailure.Code.*;

@Service
public class EmployeeAssignmentService {
    private final HrStore store;
    private final HrAssignmentStore assignments;
    private final OrganizationAssignmentGuard guard;
    private final Clock clock;
    private final TransactionTemplate root;

    public EmployeeAssignmentService(HrStore store, HrAssignmentStore assignments, OrganizationAssignmentGuard guard,
            PlatformTransactionManager manager, @Qualifier("hrClock") Clock clock,
            @Value("${kflow.hr.assignment-command-timeout-seconds:30}") int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > Integer.MAX_VALUE / 1000)
            throw new IllegalArgumentException("HR command timeout must be positive and representable in milliseconds");
        this.store=store; this.assignments=assignments; this.guard=guard; this.clock=clock;
        root=new TransactionTemplate(manager);
        root.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        root.setTimeout(timeoutSeconds);
    }

    public AssignmentChanged changeAssignment(ChangeAssignment input) {
        if (input==null) throw new HrFailure(INVALID_REFERENCE);
        var company=HrValues.reference(input.companyId()); var employeeId=HrValues.reference(input.employeeId());
        var site=HrValues.reference(input.siteId()); var department=HrValues.reference(input.departmentId());
        var position=HrValues.reference(input.positionId()); var date=HrValues.date(input.requestedEffectiveOn());
        var reason=HrValues.reason(input.reason());
        if (input.expectedEmployeeVersion()==null || input.expectedEmployeeVersion()<0) throw new HrFailure(INVALID_EXPECTED_VERSION);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new HrFailure(TRANSACTION_BOUNDARY_VIOLATION);
        try {
            return root.execute(tx -> {
                Instant now=clock.instant();
                LocalDate today=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
                if (!date.equals(today)) throw new HrFailure(UNSUPPORTED_ASSIGNMENT_DATE);
                // Guard validates the Company and actual writable RC transaction before any HR lock/write.
                guard.requireAssignable(new OrganizationAssignmentTargets(company,site,department,position));
                assignments.boundRootWaits(root.getTimeout());
                var employee=assignments.lockEmployee(company,employeeId);
                var facts=employee.identity();
                if (facts.version()!=input.expectedEmployeeVersion()) throw new HrFailure(VERSION_CONFLICT);
                if ("TERMINATED".equals(facts.employmentStatus())) throw new HrFailure(EMPLOYEE_TERMINATED);
                if (!"EMPLOYED".equals(facts.employmentStatus())) throw new HrFailure(EMPLOYMENT_STATE_UNSUPPORTED);
                var timeline=assignments.freshTimeline(company,employeeId);
                validate(employee,timeline);
                var previous=timeline.getLast();
                if (facts.terminationEffectiveOn()!=null) throw new HrFailure(HR_ASSIGNMENT_INTEGRITY_VIOLATION);
                if (previous.effectiveFrom().isAfter(today)) throw new HrFailure(HR_TIMELINE_CHANGE_UNSUPPORTED);
                if (previous.sameDestination(site,department,position)) throw new HrFailure(ASSIGNMENT_UNCHANGED);
                if (!previous.effectiveFrom().isBefore(today)) throw new HrFailure(SAME_DAY_ASSIGNMENT_CHANGE_UNSUPPORTED);

                assignments.incrementEmployeeVersion(employee); // One ORM force increment; no Employee dirty fields.
                previous.close(today,now);
                store.flush(); // Immediate EXCLUDE: the old UPDATE must complete BEFORE new persist.
                var current=new EmployeeAssignment(company,employeeId,site,department,position,today,reason,now);
                store.add(current); store.flush();
                validate(employee,assignments.freshTimeline(company,employeeId));
                return new AssignmentChanged(employeeId,employee.identity().version(),today,snapshot(previous),snapshot(current));
            });
        } catch (RuntimeException failure) {
            // Includes completion failure. Preserve technical/ambiguous outcomes; no retry or compensation.
            throw store.translate(failure);
        }
    }
    private static void validate(Employee employee, List<EmployeeAssignment> timeline) {
        var facts=employee.identity();
        AssignmentTimeline.validate(facts.joinedOn(),facts.terminationEffectiveOn(),timeline.stream()
                .map(a->new AssignmentTimeline.Period(a.effectiveFrom(),a.effectiveTo())).toList());
    }
    private static AssignmentHistoryItem snapshot(EmployeeAssignment a) {
        return new AssignmentHistoryItem(a.id(),a.companyId(),a.employeeId(),a.siteId(),a.departmentId(),a.positionId(),
                a.effectiveFrom(),a.effectiveTo(),a.reason(),a.version());
    }
}
