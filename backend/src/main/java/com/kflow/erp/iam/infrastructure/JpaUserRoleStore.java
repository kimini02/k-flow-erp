package com.kflow.erp.iam.infrastructure;

import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.domain.UserRole;
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
public class JpaUserRoleStore implements UserRoleStore {
    private final EntityManager em;private final EntityManagerFactory factory;private final int lockTimeoutMs;
    public JpaUserRoleStore(EntityManager em,EntityManagerFactory factory,@Value("${kflow.iam.lock-timeout-ms:5000}") int lockTimeoutMs){
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

    public void add(UserRole grant){em.persist(grant);}
    public void flush(){em.flush();}
    public UserFacts findUserFacts(UUID company,UUID user){return user(company,user,"");}
    public UserFacts lockUserShare(UUID company,UUID user){return user(company,user," FOR SHARE");}
    private UserFacts user(UUID company,UUID user,String lock){
        return select("select company_id,id,employee_id,account_status from iam_user where company_id=? and id=?"+lock,
                r->new UserFacts(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4)),company,user)
                .stream().findFirst().orElseThrow(()->new IamFailure(USER_NOT_FOUND));
    }
    public RoleFacts lockRoleShare(UUID company,UUID role){
        return select("select company_id,id,status from iam_role where company_id=? and id=? FOR SHARE",
                r->new RoleFacts(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)),company,role)
                .stream().findFirst().orElseThrow(()->new IamFailure(ROLE_NOT_FOUND));
    }
    public Optional<UserRoleRoute> findRoute(UUID company,UUID grant){
        return select("select company_id,id,role_id from iam_user_role where company_id=? and id=?",
                r->new UserRoleRoute(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class)),company,grant).stream().findFirst();
    }
    public UserRole lockUserRole(UUID company,UUID grant){
        var ids=select("select id from iam_user_role where company_id=? and id=? FOR UPDATE",r->r.getObject(1,UUID.class),company,grant);
        if(ids.isEmpty())throw new IamFailure(USER_ROLE_NOT_FOUND);
        var managed=em.find(UserRole.class,grant);em.refresh(managed,LockModeType.PESSIMISTIC_WRITE);return managed;
    }
    public List<UUID> unrevokedIds(UUID company,UUID role){
        // Role lock is already held by the orchestrator. PostgreSQL UUID ordering is the lock order.
        return select("select id from iam_user_role where company_id=? and role_id=? and revoked_at IS NULL order by id ASC",
                r->r.getObject(1,UUID.class),company,role);
    }
    private static final String COLUMNS="id,company_id,user_id,role_id,granted_at,grant_reason,revoked_at,revoke_reason,revoke_kind,version";
    @FunctionalInterface private interface Mapper<T>{T map(ResultSet r)throws SQLException;}
    private <T> List<T> select(String sql,Mapper<T> mapper,Object...params){
        // Bound connection and scalar facts: no ORM auto-flush, cached Entity mix, or stronger SHARE substitution.
        return em.unwrap(Session.class).doReturningWork(c->{try(var s=c.prepareStatement(sql)){
            for(int i=0;i<params.length;i++)s.setObject(i+1,params[i]);
            try(var r=s.executeQuery()){var rows=new ArrayList<T>();while(r.next())rows.add(mapper.map(r));return rows;}
        }});
    }
    private UserRoleView view(ResultSet r)throws SQLException{
        var revoked=r.getObject(7,OffsetDateTime.class);
        return new UserRoleView(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getObject(4,UUID.class),
                r.getObject(5,OffsetDateTime.class).toInstant(),r.getString(6),revoked==null?null:revoked.toInstant(),r.getString(8),r.getString(9),r.getLong(10),revoked==null?"UNREVOKED":"REVOKED");
    }
    public Optional<UserRoleView> findUserRole(UUID company,UUID grant){return select("select "+COLUMNS+" from iam_user_role where company_id=? and id=?",this::view,company,grant).stream().findFirst();}
    public List<UserRoleView> listByUser(UUID company,UUID user,String state,int offset,int limit){return list(company,user,"user_id",state,offset,limit);}
    public List<UserRoleView> listByRole(UUID company,UUID role,String state,int offset,int limit){return list(company,role,"role_id",state,offset,limit);}
    private List<UserRoleView> list(UUID company,UUID target,String column,String state,int offset,int limit){
        String filter=state==null?"":" and revoked_at IS "+("UNREVOKED".equals(state)?"NULL":"NOT NULL");
        return select("select "+COLUMNS+" from iam_user_role where company_id=? and "+column+"=?"+filter+" order by granted_at DESC,id ASC offset ? limit ?",
                this::view,company,target,offset,limit);
    }
    public RuntimeException translate(RuntimeException failure){
        for(Throwable c=failure;c!=null;c=c.getCause()){
            String state=null,name=null;
            if(c instanceof ConstraintViolationException h){state=h.getSQLState();name=h.getConstraintName();}
            else if(c instanceof PSQLException p&&p.getServerErrorMessage()!=null){state=p.getSQLState();name=p.getServerErrorMessage().getConstraint();}
            if("23505".equals(state)&&"uq_iam_user_role_unrevoked".equals(name))return new IamFailure(USER_ROLE_ALREADY_GRANTED,failure);
            if("23505".equals(state)&&"pk_iam_user_role".equals(name))return new IamFailure(GRANT_ID_CONFLICT,failure);
            if(c instanceof OptimisticLockException||c instanceof org.springframework.dao.OptimisticLockingFailureException||c instanceof org.hibernate.StaleStateException)return new IamFailure(VERSION_CONFLICT,failure);
        }
        // Preserve SQLSTATE/cause for FK/CHECK, lock timeout, deadlock, cancel, connection and commit uncertainty.
        return failure;
    }
}
