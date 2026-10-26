package com.kflow.erp.hr.application;

import com.kflow.erp.hr.api.HrFailure;
import com.kflow.erp.hr.domain.*;
import com.kflow.erp.organization.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.List;
import static com.kflow.erp.hr.api.HrFailure.Code.*;

@Service
public class EmployeeRegistrationService {
    private final HrStore store;
    private final OrganizationAssignmentGuard guard;
    private final Clock clock;
    private final TransactionTemplate root;
    public EmployeeRegistrationService(HrStore store, OrganizationAssignmentGuard guard,
            PlatformTransactionManager manager, @Qualifier("hrClock") Clock clock) {
        this.store=store; this.guard=guard; this.clock=clock;
        root=new TransactionTemplate(manager);
        root.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public EmployeeCreated createEmployee(CreateEmployee input) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new HrFailure(TRANSACTION_BOUNDARY_VIOLATION);
        if(input==null) throw new HrFailure(INVALID_REFERENCE);
        var company=HrValues.reference(input.companyId());
        var site=HrValues.reference(input.siteId()); var department=HrValues.reference(input.departmentId());
        var position=HrValues.reference(input.positionId());
        String no=HrValues.employeeNo(input.employeeNo()), name=HrValues.displayName(input.displayName()), reason=HrValues.reason(input.reason());
        var joined=HrValues.date(input.joinedOn());
        try {
            return root.execute(tx -> {
                Instant now=clock.instant(); // Exactly once per root; remains valid across a midnight lock wait.
                LocalDate date=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
                if(!joined.equals(date)) throw new HrFailure(UNSUPPORTED_JOINED_ON);
                guard.requireAssignable(new OrganizationAssignmentTargets(company,site,department,position));
                // No HR persist, dirty write or duplicate lookup precedes the full Organization guard.
                var employee=new Employee(company,no,name,joined,now);
                var assignment=new EmployeeAssignment(company,employee.id(),site,department,position,joined,reason,now);
                AssignmentTimeline.validate(joined,null,List.of(new AssignmentTimeline.Period(joined,null)));
                store.add(employee); store.flush();
                store.add(assignment); store.flush();
                return new EmployeeCreated(employee.identity(),assignment.id(),assignment.version());
            });
        } catch(RuntimeException failure) {
            // Includes transaction completion failures. Never retry/compensate or assume a lost ACK means rollback.
            throw store.translate(failure);
        }
    }
}
