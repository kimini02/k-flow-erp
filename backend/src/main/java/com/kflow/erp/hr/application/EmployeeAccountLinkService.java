package com.kflow.erp.hr.application;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.domain.HrValues;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class EmployeeAccountLinkService implements EmployeeAccountLinkGuard {
    private final HrAssignmentStore boundary;
    private final HrAccountLinkStore links;
    private final TransactionTemplate participate;
    public EmployeeAccountLinkService(HrAssignmentStore boundary, HrAccountLinkStore links, PlatformTransactionManager manager) {
        this.boundary=boundary; this.links=links;
        participate=new TransactionTemplate(manager);
        participate.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }
    public void requireLinkable(UUID company,UUID employee,LocalDate date) {
        try {
            participate.executeWithoutResult(tx -> {
                boundary.requireWritableTransaction();
                HrValues.reference(company); HrValues.reference(employee); HrValues.date(date);
                links.lockForLink(employee).requireLinkable(company,date);
            }); // Any failure marks the caller's entire root rollback-only, even if it catches the exception.
        } catch (IllegalTransactionStateException failure) {
            throw new HrFailure(HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION,failure);
        }
    }
}
