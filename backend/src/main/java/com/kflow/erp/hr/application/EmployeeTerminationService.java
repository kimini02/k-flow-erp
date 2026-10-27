package com.kflow.erp.hr.application;

import com.kflow.erp.hr.api.HrFailure;
import com.kflow.erp.hr.domain.*;
import com.kflow.erp.organization.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.List;
import static com.kflow.erp.hr.api.HrFailure.Code.*;

@Service
public class EmployeeTerminationService {
    private final HrStore store;
    private final HrAssignmentStore assignments;
    private final OrganizationReferenceQueries organization;
    private final Clock clock;
    private final TransactionTemplate root;

    public EmployeeTerminationService(HrStore store, HrAssignmentStore assignments, OrganizationReferenceQueries organization,
            PlatformTransactionManager manager, @Qualifier("hrClock") Clock clock,
            @Value("${kflow.hr.assignment-command-timeout-seconds:30}") int timeoutSeconds) {
        if (timeoutSeconds<1 || timeoutSeconds>Integer.MAX_VALUE/1000)
            throw new IllegalArgumentException("HR command timeout must be positive and representable in milliseconds");
        this.store=store; this.assignments=assignments; this.organization=organization; this.clock=clock;
        root=new TransactionTemplate(manager);
        root.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        root.setReadOnly(false); root.setTimeout(timeoutSeconds);
    }
    public EmployeeTerminated terminateEmployee(TerminateEmployee input) {
        if (input==null) throw new HrFailure(INVALID_REFERENCE);
        var company=HrValues.reference(input.companyId()); var id=HrValues.reference(input.employeeId());
        var end=HrValues.date(input.terminationEffectiveOn()); var reason=HrValues.terminationReason(input.reason());
        if (input.expectedEmployeeVersion()==null || input.expectedEmployeeVersion()<0) throw new HrFailure(INVALID_EXPECTED_VERSION);
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new HrFailure(TRANSACTION_BOUNDARY_VIOLATION);
        try {
            return root.execute(tx -> {
                Instant now=clock.instant(); // Capture once, including across a midnight lock wait.
                LocalDate today=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
                if (!end.equals(today)) throw new HrFailure(UNSUPPORTED_TERMINATION_DATE);
                // Reject a wrong manager before even a reference Port can start an unintended transaction.
                assignments.requireWritableTransaction();
                if (!organization.getCompany().companyId().equals(company))
                    throw new OrganizationFailure(OrganizationFailure.Code.COMPANY_NOT_FOUND);
                assignments.boundRootWaits(root.getTimeout());
                var employee=assignments.lockEmployee(company,id);
                var facts=employee.identity();
                if (facts.version()!=input.expectedEmployeeVersion()) throw new HrFailure(VERSION_CONFLICT);
                if ("TERMINATED".equals(facts.employmentStatus())) throw new HrFailure(ALREADY_TERMINATED);
                if (!"EMPLOYED".equals(facts.employmentStatus())) throw new HrFailure(EMPLOYMENT_STATE_UNSUPPORTED);
                var timeline=assignments.freshTimeline(company,id);
                validate(employee,timeline);
                if (facts.terminationEffectiveOn()!=null) throw new HrFailure(HR_ASSIGNMENT_INTEGRITY_VIOLATION);
                var last=timeline.getLast();
                if (last.effectiveFrom().isAfter(today)) throw new HrFailure(HR_TIMELINE_CHANGE_UNSUPPORTED);
                if (!facts.joinedOn().isBefore(end)) throw new HrFailure(SAME_DAY_TERMINATION_UNSUPPORTED);
                if (!last.effectiveFrom().isBefore(end)) throw new HrFailure(SAME_DAY_ASSIGNMENT_TERMINATION_UNSUPPORTED);
                last.close(end,now); store.flush();
                // Exactly one dirty Employee UPDATE includes status/end/reason/generation/version. No force increment.
                employee.terminate(end,reason,now); store.flush();
                validate(employee,assignments.freshTimeline(company,id));
                return new EmployeeTerminated(id,employee.identity().employmentStatus(),end,employee.identity().version(),
                        new AssignmentHistoryItem(last.id(),last.companyId(),last.employeeId(),last.siteId(),last.departmentId(),
                                last.positionId(),last.effectiveFrom(),last.effectiveTo(),last.reason(),last.version()));
            });
        } catch (RuntimeException failure) {
            throw store.translate(failure); // Includes completion errors; no automatic retry or compensation.
        }
    }
    private static void validate(Employee employee,List<EmployeeAssignment> timeline) {
        var facts=employee.identity();
        AssignmentTimeline.validate(facts.joinedOn(),facts.terminationEffectiveOn(),timeline.stream()
                .map(a->new AssignmentTimeline.Period(a.effectiveFrom(),a.effectiveTo())).toList());
    }
}
