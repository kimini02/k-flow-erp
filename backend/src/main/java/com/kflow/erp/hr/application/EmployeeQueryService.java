package com.kflow.erp.hr.application;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.domain.HrValues;
import com.kflow.erp.organization.api.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.kflow.erp.hr.api.HrFailure.Code.*;

@Service
@Transactional(readOnly=true)
public class EmployeeQueryService implements EmployeeIdentityQueries, EmployeeAssignmentQueries {
    private final HrStore store;
    private final OrganizationReferenceQueries organization;
    private final Clock clock;
    public EmployeeQueryService(HrStore store, OrganizationReferenceQueries organization, @Qualifier("hrClock") Clock clock) {
        this.store=store; this.organization=organization; this.clock=clock;
    }
    private void company(UUID id) {
        if(!organization.getCompany().companyId().equals(id))
            throw new OrganizationFailure(OrganizationFailure.Code.COMPANY_NOT_FOUND);
    }
    @Override public Optional<EmployeeIdentity> findEmployee(UUID company, UUID employee) {
        company(company); HrValues.reference(employee);
        return store.identity(company,employee);
    }
    @Override public Optional<EmployeeAssignmentFact> findAssignmentAt(UUID company, UUID employee, LocalDate asOf) {
        return at(company,employee,HrValues.date(asOf)).assignment();
    }
    public EmployeeAtDate currentDetails(UUID company, UUID employee) {
        LocalDate date=clock.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        return at(company,employee,date);
    }
    private EmployeeAtDate at(UUID company, UUID employee, LocalDate date) {
        company(company); HrValues.reference(employee);
        var rows=store.atDate(company,employee,date);
        if(rows.isEmpty()) throw new HrFailure(EMPLOYEE_NOT_FOUND);
        var identity=rows.getFirst().employee();
        var candidates=rows.stream().map(HrStore.DateRow::candidate).filter(Objects::nonNull).toList();
        boolean employed=!date.isBefore(identity.joinedOn()) && (identity.terminationEffectiveOn()==null
                || date.isBefore(identity.terminationEffectiveOn()));
        if(candidates.size()!=(employed?1:0)) throw new HrFailure(HR_ASSIGNMENT_INTEGRITY_VIOLATION);
        if(!candidates.isEmpty()) {
            var a=candidates.getFirst();
            if(a.effectiveFrom().isBefore(identity.joinedOn()) || identity.terminationEffectiveOn()!=null
                    && (a.effectiveTo()==null || a.effectiveTo().isAfter(identity.terminationEffectiveOn())))
                throw new HrFailure(HR_ASSIGNMENT_INTEGRITY_VIOLATION);
        }
        return new EmployeeAtDate(identity,date,candidates.stream().findFirst());
    }
    public HrPage<EmployeeIdentity> employees(UUID company,String status,int page,Integer requestedSize) {
        company(company); HrValues.status(status);
        int size=HrPage.size(requestedSize), offset=HrPage.offset(page,size);
        return HrPage.of(store.employees(company,status,offset,size+1),size);
    }
    public HrPage<AssignmentHistoryItem> history(UUID company, UUID employee,int page,Integer requestedSize) {
        if(findEmployee(company,employee).isEmpty()) throw new HrFailure(EMPLOYEE_NOT_FOUND);
        int size=HrPage.size(requestedSize),offset=HrPage.offset(page,size);
        return HrPage.of(store.history(company,employee,offset,size+1),size);
    }
}
