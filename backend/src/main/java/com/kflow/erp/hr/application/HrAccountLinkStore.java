package com.kflow.erp.hr.application;

import com.kflow.erp.hr.domain.EmployeeLinkFacts;
import java.util.UUID;

/** HR-internal locking read, not a Published Port. */
public interface HrAccountLinkStore {
    EmployeeLinkFacts lockForLink(UUID employeeId);
}
