package com.kflow.erp.iam.application;
import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.domain.IamValues;
import com.kflow.erp.organization.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @Transactional(readOnly=true)
public class UserQueryService {
    private final IamStore store;private final OrganizationReferenceQueries organization;
    public UserQueryService(IamStore store,OrganizationReferenceQueries organization){this.store=store;this.organization=organization;}
    private void company(UUID id){IamValues.reference(id);if(!organization.getCompany().companyId().equals(id))throw new OrganizationFailure(OrganizationFailure.Code.COMPANY_NOT_FOUND);}
    public Optional<UserView> findUser(UUID company,UUID user){company(company);return store.findUser(company,IamValues.reference(user));}
    public Optional<UserView> findUserByEmployee(UUID company,UUID employee){company(company);return store.findByEmployee(company,IamValues.reference(employee));}
    public UserPage listUsers(UUID company,String status,int page,Integer requestedSize){
        company(company);IamValues.status(status);int size=requestedSize==null?20:requestedSize;
        if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE)throw new IamFailure(IamFailure.Code.INVALID_INPUT);
        var rows=store.list(company,status,page*size,size+1);return new UserPage(rows.subList(0,Math.min(size,rows.size())),rows.size()>size);
    }
}
