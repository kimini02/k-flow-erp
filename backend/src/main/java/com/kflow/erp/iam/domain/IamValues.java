package com.kflow.erp.iam.domain;

import com.kflow.erp.iam.api.IamFailure;
import java.util.Locale;
import java.util.UUID;
import static com.kflow.erp.iam.api.IamFailure.Code.INVALID_INPUT;

public final class IamValues {
    private IamValues(){}
    public static UUID reference(UUID id){if(id==null)throw new IamFailure(INVALID_INPUT);return id;}
    public static long version(Long v){if(v==null||v<0)throw new IamFailure(INVALID_INPUT);return v;}
    public static String loginKey(String value){
        String v=trim(value);
        if(v==null||!v.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}"))throw new IamFailure(INVALID_INPUT);
        return v.toLowerCase(Locale.ROOT);
    }
    public static String reason(String value){
        String v=trim(value);
        if(v==null||v.isEmpty()||v.codePointCount(0,v.length())>500
                ||v.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c))
                ||v.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF))throw new IamFailure(INVALID_INPUT);
        return v;
    }
    private static String trim(String value){
        if(value==null)return null;int a=0,b=value.length();
        while(a<b&&value.charAt(a)==' ')a++;while(b>a&&value.charAt(b-1)==' ')b--;return value.substring(a,b);
    }
    public static String status(String value){
        if(value!=null&&!java.util.Set.of("ENABLED","DISABLED").contains(value))throw new IamFailure(INVALID_INPUT);return value;
    }
}
