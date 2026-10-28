package com.kflow.erp.hr.application;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.domain.HrValues;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class EmployeeRoleGrantService implements EmployeeRoleGrantGuard {
    private final HrAssignmentStore boundary;
    private final HrAccountLinkStore employeeRows;
    private final TransactionTemplate participate;
    public EmployeeRoleGrantService(HrAssignmentStore boundary,HrAccountLinkStore employeeRows,PlatformTransactionManager manager){
        this.boundary=boundary;this.employeeRows=employeeRows;
        participate=new TransactionTemplate(manager);participate.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }
    public void requireGrantableEmployee(UUID company,UUID employee,LocalDate referenceDate){
        try{participate.executeWithoutResult(tx->{
            boundary.requireWritableTransaction();HrValues.reference(company);HrValues.reference(employee);HrValues.date(referenceDate);
            // Reuse only HR's scalar row reader; never the account-link Guard or its eligibility result.
            employeeRows.lockForLink(employee).requireGrantableEmployee(company,referenceDate);
        });}catch(IllegalTransactionStateException failure){throw new HrFailure(HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION,failure);}
    }
}
