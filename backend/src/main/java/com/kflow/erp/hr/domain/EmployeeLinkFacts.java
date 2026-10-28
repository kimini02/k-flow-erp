package com.kflow.erp.hr.domain;

import com.kflow.erp.hr.api.HrFailure;
import java.time.LocalDate;
import java.util.UUID;
import static com.kflow.erp.hr.api.HrFailure.Code.*;

/** One locking scalar statement's facts, never a managed Employee or an authorization token. */
public record EmployeeLinkFacts(UUID companyId, String status, LocalDate joinedOn, LocalDate end, long version) {
    public void requireLinkable(UUID company, LocalDate date) {
        requireEligible(company,date,ON_LEAVE_ACCOUNT_LINK_UNSUPPORTED);
    }
    /** Role grant has a distinct purpose and ON_LEAVE contract from account creation. */
    public void requireGrantableEmployee(UUID company,LocalDate date) {
        requireEligible(company,date,ON_LEAVE_ROLE_GRANT_UNSUPPORTED);
    }
    public void requireCredentialEligibleEmployee(UUID company,LocalDate date) {
        requireEligible(company,date,ON_LEAVE_CREDENTIAL_UNSUPPORTED);
    }
    private void requireEligible(UUID company,LocalDate date,HrFailure.Code onLeaveFailure) {
        if (!company.equals(companyId)) throw new HrFailure(EMPLOYEE_COMPANY_MISMATCH);
        if (joinedOn==null || joinedOn.getYear()<1 || joinedOn.getYear()>9999 || version<0
                || status==null || !java.util.Set.of("EMPLOYED","ON_LEAVE","TERMINATED").contains(status)
                || (end!=null && (end.getYear()<1 || end.getYear()>9999 || !joinedOn.isBefore(end)))
                || ("TERMINATED".equals(status) != (end!=null))) throw new HrFailure(HR_DATA_INTEGRITY_ERROR);
        if ("TERMINATED".equals(status)) throw new HrFailure(EMPLOYEE_TERMINATED);
        if ("ON_LEAVE".equals(status)) throw new HrFailure(onLeaveFailure);
        if (joinedOn.isAfter(date)) throw new HrFailure(EMPLOYMENT_DATE_UNSUPPORTED);
    }
}
