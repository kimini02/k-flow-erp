package com.kflow.erp.iam.application;

import com.kflow.erp.hr.api.EmployeeRoleGrantGuard;
import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.domain.*;
import com.kflow.erp.organization.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.*;
import java.util.*;
import static com.kflow.erp.iam.api.IamFailure.Code.*;

@Service
public class UserRoleService {
    private final UserRoleStore store;
    private final OrganizationReferenceQueries organization;
    private final EmployeeRoleGrantGuard employees;
    private final Clock clock;
    private final TransactionTemplate root;
    public UserRoleService(UserRoleStore store,OrganizationReferenceQueries organization,EmployeeRoleGrantGuard employees,
            PlatformTransactionManager manager,@Qualifier("iamClock") Clock clock,
            @Value("${kflow.iam.command-timeout-seconds:30}") int timeout){
        if(timeout<1||timeout>Integer.MAX_VALUE/1000)throw new IllegalArgumentException("IAM timeout must be finite and positive");
        this.store=store;this.organization=organization;this.employees=employees;this.clock=clock;
        root=new TransactionTemplate(manager);root.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        root.setReadOnly(false);root.setTimeout(timeout);
    }
    public UserRoleView grantRole(GrantRole input){
        if(input==null)throw new IamFailure(INVALID_INPUT);
        UUID company=IamValues.reference(input.companyId()),grant=UserRoleValues.grantId(input.grantId()),user=IamValues.reference(input.userId()),role=IamValues.reference(input.roleId());
        String reason=IamValues.reason(input.reason());standalone();
        try{return root.execute(tx->{
            Instant now=clock.instant();LocalDate referenceDate=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
            store.requireWritableTransaction();store.boundRootWaits(root.getTimeout());company(company);
            var initial=store.findUserFacts(company,user);
            employees.requireGrantableEmployee(company,initial.employeeId(),referenceDate);
            var roleFacts=store.lockRoleShare(company,role);
            if("INACTIVE".equals(roleFacts.status()))throw new IamFailure(ROLE_INACTIVE);
            if(!"ACTIVE".equals(roleFacts.status()))throw new IamFailure(ROLE_GRANT_INTEGRITY_ERROR);
            var latest=store.lockUserShare(company,user);
            if(!initial.companyId().equals(latest.companyId())||!initial.userId().equals(latest.userId())
                    ||!initial.employeeId().equals(latest.employeeId())||latest.status()==null||!Set.of("ENABLED","DISABLED").contains(latest.status()))
                throw new IamFailure(ROLE_GRANT_INTEGRITY_ERROR);
            var value=new UserRole(company,grant,user,role,reason,now);store.add(value);store.flush();return UserRoleView.of(value);
        });}catch(RuntimeException failure){throw store.translate(failure);}
    }
    public UserRoleView revokeUserRole(RevokeUserRole input){
        if(input==null)throw new IamFailure(INVALID_INPUT);
        UUID company=IamValues.reference(input.companyId()),grant=UserRoleValues.grantId(input.grantId());
        long expected=IamValues.version(input.expectedGrantVersion());String reason=IamValues.reason(input.reason());standalone();
        try{return root.execute(tx->{
            Instant now=clock.instant();store.requireWritableTransaction();store.boundRootWaits(root.getTimeout());company(company);
            var initial=store.findRoute(company,grant).orElseThrow(()->new IamFailure(USER_ROLE_NOT_FOUND));
            var role=store.lockRoleShare(company,initial.roleId());
            var value=store.lockUserRole(company,grant);var facts=value.facts();
            if(facts.version()!=expected)throw new IamFailure(VERSION_CONFLICT);
            if(!facts.companyId().equals(initial.companyId())||!facts.roleId().equals(initial.roleId())
                    ||!facts.grantId().equals(initial.grantId())||role.status()==null||!Set.of("ACTIVE","INACTIVE").contains(role.status())
                    ||"INACTIVE".equals(role.status())&&facts.revokedAt()==null)throw new IamFailure(ROLE_GRANT_INTEGRITY_ERROR);
            if(facts.revokedAt()==null){value.revoke("MANUAL",reason,now);store.flush();}
            return UserRoleView.of(value);
        });}catch(RuntimeException failure){throw store.translate(failure);}
    }
    private static void standalone(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IamFailure(TRANSACTION_BOUNDARY_VIOLATION);}
    private void company(UUID company){if(!organization.getCompany().companyId().equals(company))throw new OrganizationFailure(OrganizationFailure.Code.COMPANY_NOT_FOUND);}
}
