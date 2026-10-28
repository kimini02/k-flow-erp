package com.kflow.erp.iam.application;

import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.domain.*;
import com.kflow.erp.organization.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.UUID;
import static com.kflow.erp.iam.api.IamFailure.Code.*;

@Service
public class RoleService {
    private final RoleStore store;
    private final UserRoleStore grants;
    private final OrganizationReferenceQueries organization;
    private final Clock clock;
    private final TransactionTemplate root;
    public RoleService(RoleStore store,UserRoleStore grants,OrganizationReferenceQueries organization,PlatformTransactionManager manager,
            @Qualifier("iamClock") Clock clock,@Value("${kflow.iam.command-timeout-seconds:30}") int timeout) {
        if(timeout<1||timeout>Integer.MAX_VALUE/1000)throw new IllegalArgumentException("IAM timeout must be finite and positive");
        this.store=store;this.grants=grants;this.organization=organization;this.clock=clock;
        root=new TransactionTemplate(manager);root.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        root.setReadOnly(false);root.setTimeout(timeout);
    }
    public RoleView createRole(CreateRole input) {
        if(input==null)throw new IamFailure(INVALID_INPUT);
        var company=IamValues.reference(input.companyId());String code=RoleValues.code(input.code()),name=RoleValues.name(input.name()),reason=IamValues.reason(input.reason());
        standalone();
        try{return root.execute(tx->{
            Instant now=clock.instant();store.requireWritableTransaction();store.boundRootWaits(root.getTimeout());company(company);
            var role=new Role(company,code,name,reason,now);store.add(role);store.flush();return RoleView.of(role);
        });}catch(RuntimeException failure){throw store.translate(failure);}
    }
    public RoleView renameRole(RenameRole input) {
        if(input==null)throw new IamFailure(INVALID_INPUT);
        return change(input.companyId(),input.roleId(),input.expectedRoleVersion(),input.reason(),RoleValues.name(input.name()),null);
    }
    public RoleView activateRole(ChangeRoleStatus input){return state(input,"ACTIVE");}
    /** One root atomically changes the Role and every current grant, including disabled users and terminated employees. */
    public RoleView deactivateRole(ChangeRoleStatus input){return state(input,"INACTIVE");}
    private RoleView state(ChangeRoleStatus input,String target) {
        if(input==null)throw new IamFailure(INVALID_INPUT);
        return change(input.companyId(),input.roleId(),input.expectedRoleVersion(),input.reason(),null,target);
    }
    private RoleView change(UUID company,UUID id,Long expectedVersion,String inputReason,String name,String status) {
        IamValues.reference(company);IamValues.reference(id);long expected=IamValues.version(expectedVersion);String reason=IamValues.reason(inputReason);standalone();
        try{return root.execute(tx->{
            Instant now=clock.instant();store.requireWritableTransaction();store.boundRootWaits(root.getTimeout());company(company);
            var role=store.lockRole(company,id);var facts=role.facts();
            if(facts.version()!=expected)throw new IamFailure(VERSION_CONFLICT);
            if(name!=null&&!facts.name().equals(name)){role.rename(name,reason,now);store.flush();}
            else if(status!=null){
                // A distinct statement after the Role UPDATE lock sees grants committed while that lock waited.
                var ids=grants.unrevokedIds(company,id);
                if("INACTIVE".equals(facts.status())&&!ids.isEmpty())throw new IamFailure(ROLE_GRANT_INTEGRITY_ERROR);
                if(!facts.status().equals(status)){
                    if("INACTIVE".equals(status)){
                        for(UUID grantId:ids){
                            var grant=grants.lockUserRole(company,grantId);var current=grant.facts();
                            if(!current.companyId().equals(company)||!current.roleId().equals(id)||!current.grantId().equals(grantId)||current.revokedAt()!=null)
                                throw new IamFailure(ROLE_GRANT_INTEGRITY_ERROR);
                            grant.revoke("ROLE_DEACTIVATION",reason,now);
                            // Per-entity flush keeps the same transaction and permits precise partial-flush rollback evidence.
                            grants.flush();
                        }
                    }
                    role.changeStatus(status,reason,now);store.flush();
                }
            }
            return RoleView.of(role);
        });}catch(RuntimeException failure){throw store.translate(failure);}
    }
    private static void standalone(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IamFailure(TRANSACTION_BOUNDARY_VIOLATION);}
    private void company(UUID company){if(!organization.getCompany().companyId().equals(company))throw new OrganizationFailure(OrganizationFailure.Code.COMPANY_NOT_FOUND);}
}
