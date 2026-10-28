package com.kflow.erp.hr.infrastructure;

import com.kflow.erp.hr.api.HrFailure;
import com.kflow.erp.hr.application.HrLoginStore;
import com.kflow.erp.hr.domain.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

@Repository
public class JpaHrLoginStore implements HrLoginStore {
  private final EntityManager em;

  public JpaHrLoginStore(EntityManager em) {
    this.em = em;
  }

  public EmployeeLoginFacts lockForLogin(UUID employee) {
    // Scalar JDBC on the bound JPA connection: no auto-flush, detach, or cached Entity state.
    return em.unwrap(Session.class)
        .doReturningWork(
            c -> {
              try (var s =
                  c.prepareStatement(
                      "select"
                          + " company_id,employment_status,joined_on,termination_effective_on,version,auth_revocation_generation"
                          + " from hr_employee where id=? FOR SHARE")) {
                s.setObject(1, employee);
                try (var r = s.executeQuery()) {
                  if (!r.next()) throw new HrFailure(HrFailure.Code.EMPLOYEE_NOT_FOUND);
                  return new EmployeeLoginFacts(
                      new EmployeeLinkFacts(
                          r.getObject(1, UUID.class),
                          r.getString(2),
                          r.getObject(3, LocalDate.class),
                          r.getObject(4, LocalDate.class),
                          r.getLong(5)),
                      r.getLong(6));
                }
              }
            });
  }
}
