package com.kflow.erp.iam.infrastructure;

import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.domain.Role;
import jakarta.persistence.*;
import org.hibernate.Session;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import static com.kflow.erp.iam.api.IamFailure.Code.*;

@Repository
public class JpaRoleStore implements RoleStore {
    private final EntityManager em;private final EntityManagerFactory factory;private final int lockTimeoutMs;
    public JpaRoleStore(EntityManager em,EntityManagerFactory factory,@Value("${kflow.iam.lock-timeout-ms:5000}") int lockTimeoutMs){
        if(lockTimeoutMs<1)throw new IllegalArgumentException("IAM lock timeout must be positive");
        this.em=em;this.factory=factory;this.lockTimeoutMs=lockTimeoutMs;
    }
    public void requireWritableTransaction(){
        Integer isolation=TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        Object resource=TransactionSynchronizationManager.getResource(factory);
        if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ||isolation!=null&&isolation!=Connection.TRANSACTION_READ_COMMITTED
                ||!(resource instanceof EntityManagerHolder holder)||!holder.getEntityManager().isJoinedToTransaction()||!em.isJoinedToTransaction())
            throw new IamFailure(TRANSACTION_BOUNDARY_VIOLATION);
        em.unwrap(Session.class).doWork(c->{
            if(c.getAutoCommit()||c.isReadOnly()||c.getTransactionIsolation()!=Connection.TRANSACTION_READ_COMMITTED)throw new IamFailure(TRANSACTION_BOUNDARY_VIOLATION);
            try(var s=c.createStatement();var r=s.executeQuery("select current_setting('transaction_isolation'),current_setting('transaction_read_only')")){
                if(!r.next()||!"read committed".equals(r.getString(1))||!"off".equals(r.getString(2)))throw new IamFailure(TRANSACTION_BOUNDARY_VIOLATION);
            }
        });
    }
    public void boundRootWaits(int seconds){
        em.unwrap(Session.class).doWork(c->{try(var s=c.prepareStatement("select set_config('lock_timeout',?,true),set_config('statement_timeout',?,true)")){
            s.setString(1,lockTimeoutMs+"ms");s.setString(2,seconds*1000+"ms");s.execute();
        }});
    }
    public void add(Role role){em.persist(role);}
    public void flush(){em.flush();}
    public Role lockRole(UUID company,UUID role){
        var ids=select("select id from iam_role where company_id=? and id=? FOR UPDATE",r->r.getObject(1,UUID.class),company,role);
        if(ids.isEmpty())throw new IamFailure(ROLE_NOT_FOUND);
        var managed=em.find(Role.class,role);em.refresh(managed,LockModeType.PESSIMISTIC_WRITE);return managed;
    }
    private static final String COLUMNS="id,company_id,code,name,status,version,creation_reason,last_change_reason,last_change_kind,created_at,updated_at";
    @FunctionalInterface private interface Mapper<T>{T map(ResultSet r)throws SQLException;}
    private <T> List<T> select(String sql,Mapper<T> mapper,Object...params){
        // Existing transaction connection, scalar facts, no query auto-flush or managed-state mixing.
        return em.unwrap(Session.class).doReturningWork(c->{try(var s=c.prepareStatement(sql)){
            for(int i=0;i<params.length;i++)s.setObject(i+1,params[i]);
            try(var r=s.executeQuery()){var rows=new ArrayList<T>();while(r.next())rows.add(mapper.map(r));return rows;}
        }});
    }
    private RoleView view(ResultSet r)throws SQLException{
        return new RoleView(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5),r.getLong(6),
                r.getString(7),r.getString(8),r.getString(9),r.getObject(10,OffsetDateTime.class).toInstant(),r.getObject(11,OffsetDateTime.class).toInstant());
    }
    public Optional<RoleView> findRole(UUID company,UUID role){return select("select "+COLUMNS+" from iam_role where company_id=? and id=?",this::view,company,role).stream().findFirst();}
    public Optional<RoleView> findByCode(UUID company,String code){return select("select "+COLUMNS+" from iam_role where company_id=? and code=?",this::view,company,code).stream().findFirst();}
    public List<RoleListItem> list(UUID company,String status,int offset,int limit){
        String sql="select id,code,name,status,version from iam_role where company_id=?"+(status==null?"":" and status=?")+" order by code,id offset ? limit ?";
        Mapper<RoleListItem> mapper=r->new RoleListItem(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getLong(5));
        return status==null?select(sql,mapper,company,offset,limit):select(sql,mapper,company,status,offset,limit);
    }
    public RuntimeException translate(RuntimeException failure){
        for(Throwable c=failure;c!=null;c=c.getCause()){
            String state=null,name=null;
            if(c instanceof ConstraintViolationException h){state=h.getSQLState();name=h.getConstraintName();}
            else if(c instanceof PSQLException p&&p.getServerErrorMessage()!=null){state=p.getSQLState();name=p.getServerErrorMessage().getConstraint();}
            if("23505".equals(state)&&"uq_iam_role_company_code".equals(name))return new IamFailure(ROLE_CODE_CONFLICT,failure);
            if(c instanceof OptimisticLockException||c instanceof org.springframework.dao.OptimisticLockingFailureException||c instanceof org.hibernate.StaleStateException)return new IamFailure(VERSION_CONFLICT,failure);
        }
        return failure;
    }
}
