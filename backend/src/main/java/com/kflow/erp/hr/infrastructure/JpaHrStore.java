package com.kflow.erp.hr.infrastructure;

import com.kflow.erp.hr.api.*;
import com.kflow.erp.hr.application.*;
import com.kflow.erp.hr.domain.*;
import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.postgresql.util.PSQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import static com.kflow.erp.hr.api.HrFailure.Code.*;

@Repository
public class JpaHrStore implements HrStore {
    private final EntityManager em;
    public JpaHrStore(EntityManager em) { this.em=em; }
    public void add(Employee employee) { em.persist(employee); }
    public void add(EmployeeAssignment assignment) { em.persist(assignment); }
    public void flush() { em.flush(); }
    private static final String EMPLOYEE_COLUMNS="e.id,e.company_id,e.employee_no,e.display_name,e.employment_status,e.joined_on,e.termination_effective_on,e.version";
    @FunctionalInterface private interface Mapper<T> { T map(ResultSet row) throws SQLException; }
    private <T> List<T> select(String sql, Mapper<T> mapper,Object... params) {
        // Use the existing transaction's connection. JDBC scalar reads neither consult nor flush the persistence context.
        return em.unwrap(Session.class).doReturningWork(connection -> {
            try(var stmt=connection.prepareStatement(sql)) {
                for(int i=0;i<params.length;i++) stmt.setObject(i+1,params[i]);
                try(var rows=stmt.executeQuery()) {
                    var result=new ArrayList<T>();
                    while(rows.next()) result.add(mapper.map(rows));
                    return result;
                }
            }
        });
    }
    private EmployeeIdentity identity(ResultSet r) throws SQLException {
        return new EmployeeIdentity(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),
                r.getString(5),r.getObject(6,LocalDate.class),r.getObject(7,LocalDate.class),r.getLong(8));
    }
    public Optional<EmployeeIdentity> identity(UUID company,UUID employee) {
        return select("select "+EMPLOYEE_COLUMNS+" from hr_employee e where e.company_id=? and e.id=?",this::identity,company,employee)
                .stream().findFirst();
    }
    public List<DateRow> atDate(UUID company,UUID employee,LocalDate date) {
        return select("select "+EMPLOYEE_COLUMNS+",a.id,a.site_id,a.department_id,a.position_id,a.effective_from,a.effective_to "
                +"from hr_employee e left join hr_employee_assignment a on a.company_id=e.company_id and a.employee_id=e.id "
                +"and a.effective_from<=? and (a.effective_to is null or ?<a.effective_to) "
                +"where e.company_id=? and e.id=? order by a.effective_from,a.id limit 2",r -> {
            var employeeFact=identity(r);
            var id=r.getObject(9,UUID.class);
            return new DateRow(employeeFact,id==null?null:new EmployeeAssignmentFact(employeeFact,id,
                    r.getObject(10,UUID.class),r.getObject(11,UUID.class),r.getObject(12,UUID.class),
                    r.getObject(13,LocalDate.class),r.getObject(14,LocalDate.class)));
        },date,date,company,employee);
    }
    public List<EmployeeIdentity> employees(UUID company,String status,int offset,int limit) {
        String sql="select "+EMPLOYEE_COLUMNS+" from hr_employee e where e.company_id=?"
                +(status==null?"":" and e.employment_status=?")+" order by e.employee_no,e.id offset ? limit ?";
        return status==null?select(sql,this::identity,company,offset,limit):select(sql,this::identity,company,status,offset,limit);
    }
    public List<AssignmentHistoryItem> history(UUID company,UUID employee,int offset,int limit) {
        return select("select id,company_id,employee_id,site_id,department_id,position_id,effective_from,effective_to,reason,version "
                +"from hr_employee_assignment where company_id=? and employee_id=? order by effective_from desc,id asc offset ? limit ?",
                r->new AssignmentHistoryItem(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),
                        r.getObject(4,UUID.class),r.getObject(5,UUID.class),r.getObject(6,UUID.class),r.getObject(7,LocalDate.class),
                        r.getObject(8,LocalDate.class),r.getString(9),r.getLong(10)),company,employee,offset,limit);
    }
    public RuntimeException translate(RuntimeException failure) {
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            String state=null, name=null;
            if(cause instanceof ConstraintViolationException c) {
                state=c.getSQLState(); name=c.getConstraintName();
            } else if(cause instanceof PSQLException p && p.getServerErrorMessage()!=null) {
                // Transaction completion may carry only driver metadata, without a Hibernate constraint wrapper.
                state=p.getSQLState(); name=p.getServerErrorMessage().getConstraint();
            }
            if("23505".equals(state) && "uq_hr_employee_company_employee_no".equals(name))
                return new HrFailure(EMPLOYEE_NO_CONFLICT,failure);
            if("23P01".equals(state) && "ex_hr_assignment_employee_period".equals(name))
                return new HrFailure(ASSIGNMENT_PERIOD_CONFLICT,failure);
        }
        return failure; // FK/CHECK/PK, unknown constraints, timeout/deadlock/connection/commit ambiguity remain technical.
    }
}
