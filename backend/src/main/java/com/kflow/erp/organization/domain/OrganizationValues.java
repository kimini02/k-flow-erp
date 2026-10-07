package com.kflow.erp.organization.domain;

import java.util.Locale;
import com.kflow.erp.organization.api.OrganizationFailure;
import static com.kflow.erp.organization.api.OrganizationFailure.Code.*;

public final class OrganizationValues {
    private OrganizationValues() {}
    private static String spaces(String value) {
        int first = 0, last = value.length();
        while (first < last && value.charAt(first) == ' ') first++;
        while (last > first && value.charAt(last - 1) == ' ') last--;
        return value.substring(first, last);
    }
    public static String code(String input) {
        if (input == null) throw new OrganizationFailure(INVALID_ORGANIZATION_CODE);
        String value = spaces(input);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}"))
            throw new OrganizationFailure(INVALID_ORGANIZATION_CODE);
        return value.toUpperCase(Locale.ROOT);
    }
    public static String name(String input) {
        if (input == null) throw new OrganizationFailure(INVALID_ORGANIZATION_NAME);
        String value = spaces(input);
        if (value.isEmpty() || value.codePointCount(0, value.length()) > 100
                || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                || value.codePoints().anyMatch(c -> Character.isISOControl(c)
                    || Character.getType(c) == Character.SURROGATE))
            throw new OrganizationFailure(INVALID_ORGANIZATION_NAME);
        return value;
    }
    public static void expectedVersion(long value) {
        if (value < 0) throw new OrganizationFailure(INVALID_VERSION);
    }
}
