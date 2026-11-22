package com.kflow.erp.masterdata.domain;

import com.kflow.erp.masterdata.api.ItemFailure;
import com.kflow.erp.masterdata.api.Unit;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import static com.kflow.erp.masterdata.api.ItemFailure.Code.*;

public final class ItemValues {
    private ItemValues() {}
    public static UUID reference(UUID value) {
        if (value == null || value.equals(new UUID(0, 0))) throw new ItemFailure(INVALID_INPUT);
        return value;
    }
    public static long version(Long value) {
        if (value == null || value < 0) throw new ItemFailure(INVALID_INPUT);
        return value;
    }
    public static String code(String input) {
        String value = trim(input);
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}"))
            throw new ItemFailure(INVALID_INPUT);
        return value.toUpperCase(Locale.ROOT);
    }
    public static String name(String input) { return text(input, 100, false); }
    public static String specification(String input) { return text(input, 500, true); }
    public static String text(String input, int max, boolean optional) {
        String value = trim(input);
        if (optional && (value == null || value.isEmpty())) return null;
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) > max
                || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xD800 && c <= 0xDFFF))
            throw new ItemFailure(INVALID_INPUT);
        return value;
    }
    private static String trim(String input) {
        if (input == null) return null;
        int from = 0, to = input.length();
        while (from < to && input.charAt(from) == ' ') from++;
        while (to > from && input.charAt(to - 1) == ' ') to--;
        return input.substring(from, to);
    }
    public static Unit unit(Unit value) {
        if (value == null) throw new ItemFailure(INVALID_INPUT);
        return value;
    }
    public static void profile(String category, String profile, Boolean stockManaged) {
        if (stockManaged == null) throw new ItemFailure(INVALID_INPUT);
        boolean supported = Boolean.TRUE.equals(stockManaged) && (
                "FINISHED_GOOD".equals(category) && "MANUFACTURED_FINISHED".equals(profile)
                || "RAW_MATERIAL".equals(category) && "PURCHASED_MATERIAL".equals(profile)
                || "MERCHANDISE".equals(category) && "RESALE".equals(profile));
        if (!supported) throw new ItemFailure(UNSUPPORTED_ITEM_PROFILE);
    }
    public static String status(String value) {
        if (!"ACTIVE".equals(value) && !"INACTIVE".equals(value)) throw new ItemFailure(INVALID_INPUT);
        return value;
    }
    public static BigDecimal price(BigDecimal value) {
        if (value == null) return null;
        if (value.signum() < 0 || value.compareTo(new BigDecimal("999999999999")) > 0
                || value.stripTrailingZeros().scale() > 0) throw new ItemFailure(INVALID_INPUT);
        return value.setScale(0); // Exact: fractional values have already been rejected.
    }
    public static void quantity(Unit unit, BigDecimal value) {
        unit(unit);
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > unit.quantityScale())
            throw new ItemFailure(INVALID_INPUT);
    }
}
