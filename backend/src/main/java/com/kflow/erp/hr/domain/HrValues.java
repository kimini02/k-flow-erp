package com.kflow.erp.hr.domain;

import com.kflow.erp.hr.api.HrFailure;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import static com.kflow.erp.hr.api.HrFailure.Code.*;

public final class HrValues {
    private HrValues() {}
    public static String employeeNo(String value) {
        String v = trim(value);
        if (v == null || !v.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}"))
            throw new HrFailure(INVALID_EMPLOYEE_NO);
        return v.toUpperCase(Locale.ROOT);
    }
    public static String displayName(String value) { return text(value, 100, INVALID_DISPLAY_NAME); }
    public static String reason(String value) { return text(value, 500, INVALID_ASSIGNMENT_REASON); }
    public static String terminationReason(String value) { return text(value, 500, INVALID_TERMINATION_REASON); }
    private static String text(String value, int max, HrFailure.Code code) {
        String v = trim(value);
        if (v == null || v.isEmpty() || v.codePointCount(0,v.length()) > max
                || v.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                || v.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF))
            throw new HrFailure(code);
        return v;
    }
    private static String trim(String value) {
        if (value == null) return null;
        int a=0,b=value.length();
        while(a<b && value.charAt(a)==' ') a++;
        while(b>a && value.charAt(b-1)==' ') b--;
        return value.substring(a,b);
    }
    public static UUID reference(UUID id) { if(id==null) throw new HrFailure(INVALID_REFERENCE); return id; }
    public static LocalDate date(LocalDate date) {
        if(date==null || date.getYear()<1 || date.getYear()>9999) throw new HrFailure(INVALID_DATE);
        return date;
    }
    public static String status(String status) {
        if(status!=null && !java.util.Set.of("EMPLOYED","ON_LEAVE","TERMINATED").contains(status))
            throw new HrFailure(INVALID_EMPLOYMENT_STATUS);
        return status;
    }
}
