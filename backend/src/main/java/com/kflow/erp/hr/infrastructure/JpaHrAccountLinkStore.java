package com.kflow.erp.hr.infrastructure;

import com.kflow.erp.hr.api.HrFailure;
import com.kflow.erp.hr.application.HrAccountLinkStore;
import com.kflow.erp.hr.domain.EmployeeLinkFacts;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

@Repository
public class JpaHrAccountLinkStore implements HrAccountLinkStore {
    private final EntityManager em;
    private final int lockTimeoutMs;
    public JpaHrAccountLinkStore(EntityManager em,@Value("${kflow.hr.account-link-lock-timeout-ms:5000}") int lockTimeoutMs) {
        if(lockTimeoutMs<1)throw new IllegalArgumentException("HR link lock timeout must be positive");
        this.em=em;this.lockTimeoutMs=lockTimeoutMs;
    }
    public EmployeeLinkFacts lockForLink(UUID employee) {
        return em.unwrap(Session.class).doReturningWork(c -> {
            String previous;
            try(var s=c.createStatement();var r=s.executeQuery("show lock_timeout")){r.next();previous=r.getString(1);}
            boolean configured="0".equals(previous);
            if(configured)timeout(c,lockTimeoutMs+"ms");
            // No ORM query auto-flush, state predicate, Entity getter, key-share substitution or refresh.
            try(var s=c.prepareStatement("select company_id,employment_status,joined_on,termination_effective_on,version from hr_employee where id=? FOR SHARE")) {
                s.setObject(1,employee);
                try(var r=s.executeQuery()) {
                    if(!r.next())throw new HrFailure(HrFailure.Code.EMPLOYEE_NOT_FOUND);
                    var facts=new EmployeeLinkFacts(r.getObject(1,UUID.class),r.getString(2),r.getObject(3,LocalDate.class),r.getObject(4,LocalDate.class),r.getLong(5));
                    if(configured)timeout(c,previous);
                    return facts;
                }
            } // A failed SQL aborts the root; never run restoration SQL in an aborted transaction.
        });
    }
    private static void timeout(Connection c,String value)throws SQLException {
        try(var s=c.prepareStatement("select set_config('lock_timeout',?,true)")){s.setString(1,value);s.execute();}
    }
}
