package com.kflow.erp.iam.domain;
import com.kflow.erp.iam.api.IamFailure;
import java.util.UUID;
import static com.kflow.erp.iam.api.IamFailure.Code.INVALID_INPUT;
public final class UserRoleValues {
    private UserRoleValues(){}
    public static UUID grantId(UUID id){if(id==null||id.version()!=4||id.variant()!=2)throw new IamFailure(INVALID_INPUT);return id;}
    public static String state(String value){if(value!=null&&!java.util.Set.of("UNREVOKED","REVOKED").contains(value))throw new IamFailure(INVALID_INPUT);return value;}
}
