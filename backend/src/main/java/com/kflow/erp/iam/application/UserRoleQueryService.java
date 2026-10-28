package com.kflow.erp.iam.application;

import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.domain.*;
import com.kflow.erp.organization.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.kflow.erp.iam.api.IamFailure.Code.*;

@Service @Transactional(readOnly=true)
public class UserRoleQueryService {
    private final UserRoleStore store;private final IamStore users;private final RoleStore roles;
    private final OrganizationReferenceQueries organization;
    public UserRoleQueryService(UserRoleStore store,IamStore users,RoleStore roles,OrganizationReferenceQueries organization){this.store=store;this.users=users;this.roles=roles;this.organization=organization;}
    public Optional<UserRoleView> findUserRole(UUID company,UUID grant){company(company);return store.findUserRole(company,UserRoleValues.grantId(grant));}
    public UserRolePage listUserRoleHistoryByUser(UUID company,UUID user,String state,int page,Integer requestedSize){
        int size=page(state,page,requestedSize);IamValues.reference(user);company(company);
        if(users.findUser(company,user).isEmpty())throw new IamFailure(USER_NOT_FOUND);
        return result(store.listByUser(company,user,state,page*size,size+1),size);
    }
    public UserRolePage listUserRoleHistoryByRole(UUID company,UUID role,String state,int page,Integer requestedSize){
        int size=page(state,page,requestedSize);IamValues.reference(role);company(company);
        if(roles.findRole(company,role).isEmpty())throw new IamFailure(ROLE_NOT_FOUND);
        return result(store.listByRole(company,role,state,page*size,size+1),size);
    }
    private static int page(String state,int page,Integer requestedSize){
        UserRoleValues.state(state);int size=requestedSize==null?20:requestedSize;
        if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE)throw new IamFailure(INVALID_INPUT);return size;
    }
    private static UserRolePage result(List<UserRoleView> rows,int size){return new UserRolePage(rows.subList(0,Math.min(size,rows.size())),rows.size()>size);}
    private void company(UUID id){IamValues.reference(id);if(!organization.getCompany().companyId().equals(id))throw new OrganizationFailure(OrganizationFailure.Code.COMPANY_NOT_FOUND);}
}
