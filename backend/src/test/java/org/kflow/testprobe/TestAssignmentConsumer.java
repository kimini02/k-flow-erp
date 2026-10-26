package org.kflow.testprobe;

import com.kflow.erp.organization.api.*;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Only Published API imports. A provider test path, not an HR implementation. */
public class TestAssignmentConsumer {
    private final OrganizationAssignmentGuard guard;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate root;
    public TestAssignmentConsumer(OrganizationAssignmentGuard guard, EntityManager em, JdbcTemplate jdbc,
                                  PlatformTransactionManager manager) {
        this.guard=guard; this.em=em; this.jdbc=jdbc; this.root=new TransactionTemplate(manager);
    }
    public List<Map<String,Object>> consume(OrganizationAssignmentTargets targets, UUID probe, String label,
                                            Runnable beforeGuard, Runnable afterGuard, boolean fail) {
        return root.execute(tx -> {
            var loaded=em.find(ManagedProbe.class,probe); // read only before Guard
            var mode=em.getFlushMode();
            var observations=new ArrayList<Map<String,Object>>();
            observations.add(identity());
            beforeGuard.run();
            guard.requireAssignable(targets);
            observations.add(identity());
            assertThat(em.contains(loaded)).isTrue();
            assertThat(em.getFlushMode()).isEqualTo(mode);
            afterGuard.run();
            loaded.label(label); // managed dirty checking must survive Guard
            jdbc.update("insert into org03_test_reference(id,site_id,department_id,position_id) values (?,?,?,?)",
                    UUID.randomUUID(),targets.siteId(),targets.departmentId(),targets.positionId());
            observations.add(identity());
            if(fail) throw new IllegalStateException("test consumer abort");
            return observations;
        });
    }
    private Map<String,Object> identity() {
        return jdbc.queryForMap("select pg_backend_pid() pid, pg_current_xact_id()::text xid");
    }
}
