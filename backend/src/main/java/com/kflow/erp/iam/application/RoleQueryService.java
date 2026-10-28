package com.kflow.erp.iam.application;
import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.domain.*;
import com.kflow.erp.organization.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @Transactional(readOnly=true)
public class RoleQueryService {
    private final RoleStore store;private final OrganizationReferenceQueries organization;
    public RoleQueryService(RoleStore store,OrganizationReferenceQueries organization){this.store=store;this.organization=organization;}
    private void company(UUID id){IamValues.reference(id);if(!organization.getCompany().companyId().equals(id))throw new OrganizationFailure(OrganizationFailure.Code.COMPANY_NOT_FOUND);}
    public Optional<RoleView> findRole(UUID company,UUID role){company(company);return store.findRole(company,IamValues.reference(role));}
    public Optional<RoleView> findRoleByCode(UUID company,String code){String canonical=RoleValues.code(code);company(company);return store.findByCode(company,canonical);}
    public RolePage listRoles(UUID company,String status,int page,Integer requestedSize){
        RoleValues.status(status);int size=requestedSize==null?20:requestedSize;
        if(page<0||size<1||size>100||(long)page*size>Integer.MAX_VALUE)throw new IamFailure(IamFailure.Code.INVALID_INPUT);
        company(company);var rows=store.list(company,status,page*size,size+1);
        return new RolePage(rows.subList(0,Math.min(size,rows.size())),rows.size()>size);
    }
}
