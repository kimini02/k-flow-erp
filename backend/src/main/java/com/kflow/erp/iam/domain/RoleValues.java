package com.kflow.erp.iam.domain;

import com.kflow.erp.iam.api.IamFailure;
import java.util.Locale;
import static com.kflow.erp.iam.api.IamFailure.Code.INVALID_INPUT;

/** Role codes intentionally differ from login keys. No shared cross-module value model. */
public final class RoleValues {
    private RoleValues() {}
    public static String code(String input) {
        String value=trim(input);
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}"))throw new IamFailure(INVALID_INPUT);
        return value.toUpperCase(Locale.ROOT);
    }
    public static String name(String input) {
        String value=trim(input);
        if(value==null||value.isEmpty()||value.codePointCount(0,value.length())>100
                ||value.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c))
                ||value.codePoints().anyMatch(c->Character.isISOControl(c)||c>=0xD800&&c<=0xDFFF))throw new IamFailure(INVALID_INPUT);
        return value;
    }
    public static String status(String value) {
        if(value!=null&&!java.util.Set.of("ACTIVE","INACTIVE").contains(value))throw new IamFailure(INVALID_INPUT);
        return value;
    }
    private static String trim(String value) {
        if(value==null)return null;int a=0,b=value.length();
        while(a<b&&value.charAt(a)==' ')a++;while(b>a&&value.charAt(b-1)==' ')b--;return value.substring(a,b);
    }
}
