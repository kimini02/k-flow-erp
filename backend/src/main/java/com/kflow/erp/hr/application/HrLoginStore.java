package com.kflow.erp.hr.application;

import com.kflow.erp.hr.domain.EmployeeLoginFacts;
import java.util.UUID;

public interface HrLoginStore {
  EmployeeLoginFacts lockForLogin(UUID employee);
}
