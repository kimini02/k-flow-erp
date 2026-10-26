package com.kflow.erp.hr.infrastructure;

import com.kflow.erp.hr.api.HrFailure;
import com.kflow.erp.hr.application.HrAssignmentStore;
import com.kflow.erp.hr.domain.*;
import jakarta.persistence.*;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.util.*;
import static com.kflow.erp.hr.api.HrFailure.Code.EMPLOYEE_NOT_FOUND;

@Repository
public class JpaHrAssignmentStore implements HrAssignmentStore {
    private final EntityManager em;
    private final int lockTimeoutMs;
    public JpaHrAssignmentStore(EntityManager em, @Value("${kflow.hr.assignment-lock-timeout-ms:5000}") int lockTimeoutMs) {
        if (lockTimeoutMs < 1) throw new IllegalArgumentException("HR lock timeout must be positive");
        this.em=em; this.lockTimeoutMs=lockTimeoutMs;
    }
    public void boundRootWaits(int timeoutSeconds) {
        // Transaction-local limits also cover Hibernate/JDBC statements using this same physical connection.
        em.unwrap(Session.class).doWork(c -> {
            try (var s=c.prepareStatement("select set_config('lock_timeout', ?, true), set_config('statement_timeout', ?, true)")) {
                s.setString(1,lockTimeoutMs+"ms"); s.setString(2,(timeoutSeconds*1000)+"ms"); s.execute();
            }
        });
    }
    public Employee lockEmployee(UUID company, UUID employee) {
        // Explicit PostgreSQL FOR UPDATE (not KEY SHARE), without auto-flushing unrelated managed objects.
        boolean exists=em.unwrap(Session.class).doReturningWork(c -> {
            try (var s=c.prepareStatement("select id from hr_employee where company_id=? and id=? FOR UPDATE")) {
                s.setObject(1,company); s.setObject(2,employee);
                try (var r=s.executeQuery()) { return r.next(); }
            }
        });
        if (!exists) throw new HrFailure(EMPLOYEE_NOT_FOUND);
        var managed=em.find(Employee.class,employee);
        // The physical lock alone does not refresh an existing first-level-cache entry.
        em.refresh(managed,LockModeType.PESSIMISTIC_WRITE);
        return managed;
    }
    public List<EmployeeAssignment> freshTimeline(UUID company, UUID employee) {
        // Re-read the complete ID set AFTER the Employee lock; a preloaded list may miss a newly committed tail.
        List<UUID> ids=em.unwrap(Session.class).doReturningWork(c -> {
            try (var s=c.prepareStatement("select id from hr_employee_assignment where company_id=? and employee_id=? order by effective_from,id")) {
                s.setObject(1,company); s.setObject(2,employee);
                try (var r=s.executeQuery()) {
                    var result=new ArrayList<UUID>(); while(r.next()) result.add(r.getObject(1,UUID.class)); return result;
                }
            }
        });
        return ids.stream().map(id -> {
            var managed=em.find(EmployeeAssignment.class,id);
            em.refresh(managed); // Local only; no EntityManager.clear/detach or query auto-flush.
            return managed;
        }).toList();
    }
    public void incrementEmployeeVersion(Employee employee) {
        em.lock(employee,LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }
}
