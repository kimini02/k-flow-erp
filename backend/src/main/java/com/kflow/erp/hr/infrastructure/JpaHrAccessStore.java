package com.kflow.erp.hr.infrastructure;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.api.EmployeeAccessFactsQueries.*;
import com.kflow.erp.hr.application.HrAccessStore;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.*;
import javax.sql.DataSource;
import org.hibernate.Session;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JpaHrAccessStore implements HrAccessStore {
  private final EntityManager em;
  private final DataSource primary;

  public JpaHrAccessStore(EntityManager em, DataSource primary) {
    this.em = em;
    this.primary = primary;
  }

  public Optional<Facts> findAtDate(UUID employee, LocalDate date) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw boundary();
    return em.unwrap(Session.class)
        .doReturningWork(
            c -> {
              if (!DataSourceUtils.isConnectionTransactional(c, primary)
                  || c.getAutoCommit()
                  || !c.isReadOnly()
                  || c.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ)
                throw boundary();
              try (var s = c.createStatement()) {
                s.setQueryTimeout(5);
                try (var r =
                    s.executeQuery(
                        "select current_setting('transaction_isolation'),"
                            + " current_setting('transaction_read_only'), pg_is_in_recovery()")) {
                  if (!r.next()
                      || !"repeatable read".equals(r.getString(1))
                      || !"on".equals(r.getString(2))
                      || r.getBoolean(3)) throw boundary();
                }
              }
              // One HR scalar projection. No LIMIT 1, entities, lazy loading, flush, detach or
              // clear.
              try (var s =
                  c.prepareStatement(
                      """
select e.id,e.company_id,e.employment_status,e.joined_on,e.termination_effective_on,
       e.auth_revocation_generation,a.id,a.company_id,a.employee_id,a.site_id,
       a.department_id,a.position_id,a.effective_from,a.effective_to
from hr_employee e left join hr_employee_assignment a on a.employee_id=e.id
  and a.effective_from<=? and (a.effective_to is null or ?<a.effective_to)
where e.id=? order by a.id
""")) {
                s.setQueryTimeout(5);
                s.setObject(1, date);
                s.setObject(2, date);
                s.setObject(3, employee);
                try (var r = s.executeQuery()) {
                  if (!r.next()) return Optional.empty();
                  var id = r.getObject(1, UUID.class);
                  var company = r.getObject(2, UUID.class);
                  var status = r.getString(3);
                  var joined = r.getObject(4, LocalDate.class);
                  var terminated = r.getObject(5, LocalDate.class);
                  long generation = r.getLong(6);
                  var candidates = new ArrayList<Assignment>();
                  do {
                    if (r.getObject(7) != null)
                      candidates.add(
                          new Assignment(
                              r.getObject(7, UUID.class),
                              r.getObject(8, UUID.class),
                              r.getObject(9, UUID.class),
                              r.getObject(10, UUID.class),
                              r.getObject(11, UUID.class),
                              r.getObject(12, UUID.class),
                              r.getObject(13, LocalDate.class),
                              r.getObject(14, LocalDate.class)));
                  } while (r.next());
                  return Optional.of(
                      new Facts(id, company, status, joined, terminated, generation, candidates));
                }
              }
            });
  }

  private static HrFailure boundary() {
    return new HrFailure(HrFailure.Code.TRANSACTION_BOUNDARY_VIOLATION);
  }
}
