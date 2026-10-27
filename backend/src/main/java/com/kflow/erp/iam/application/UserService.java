package com.kflow.erp.iam.application;
import com.kflow.erp.hr.api.EmployeeAccountLinkGuard;
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
public class UserService {
    private final IamStore store;
    private final EmployeeAccountLinkGuard guard;
    private final OrganizationReferenceQueries organization;
    private final Clock clock;
    private final TransactionTemplate root;
    public UserService(IamStore store,EmployeeAccountLinkGuard guard,OrganizationReferenceQueries organization,
            PlatformTransactionManager manager,@Qualifier("iamClock") Clock clock,
            @Value("${kflow.iam.command-timeout-seconds:30}") int timeout){
        if(timeout<1||timeout>Integer.MAX_VALUE/1000)throw new IllegalArgumentException("IAM timeout must be finite and positive");
        this.store=store;this.guard=guard;this.organization=organization;this.clock=clock;
        root=new TransactionTemplate(manager);root.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        root.setReadOnly(false);root.setTimeout(timeout);
    }
    public UserView createUser(CreateUser input){
        if(input==null)throw new IamFailure(INVALID_INPUT);
        var company=IamValues.reference(input.companyId());var employee=IamValues.reference(input.employeeId());
        String key=IamValues.loginKey(input.loginKey()),reason=IamValues.reason(input.reason());standalone();
        try{return root.execute(tx->{
            Instant now=clock.instant();LocalDate date=now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
            store.requireWritableTransaction();company(company);store.boundRootWaits(root.getTimeout());
            guard.requireLinkable(company,employee,date);
            var user=new User(company,employee,key,reason,now);store.add(user);store.flush();return UserView.of(user);
        });}catch(RuntimeException failure){throw store.translate(failure);}
    }
    public UserView enableUser(ChangeUserStatus input){return change(input,"ENABLED");}
    public UserView disableUser(ChangeUserStatus input){return change(input,"DISABLED");}
    private UserView change(ChangeUserStatus input,String target){
        if(input==null)throw new IamFailure(INVALID_INPUT);
        var company=IamValues.reference(input.companyId());var id=IamValues.reference(input.userId());
        long expected=IamValues.version(input.expectedUserVersion());String reason=IamValues.reason(input.reason());standalone();
        try{return root.execute(tx->{
            Instant now=clock.instant();store.requireWritableTransaction();company(company);store.boundRootWaits(root.getTimeout());
            var user=store.lockUser(company,id);
            if(user.facts().version()!=expected)throw new IamFailure(VERSION_CONFLICT);
            if(!user.facts().accountStatus().equals(target)){user.changeStatus(target,reason,now);store.flush();}
            return UserView.of(user);
        });}catch(RuntimeException failure){throw store.translate(failure);}
    }
    private static void standalone(){if(TransactionSynchronizationManager.isActualTransactionActive())throw new IamFailure(TRANSACTION_BOUNDARY_VIOLATION);}
    private void company(UUID company){if(!organization.getCompany().companyId().equals(company))throw new OrganizationFailure(OrganizationFailure.Code.COMPANY_NOT_FOUND);}
}
