package com.kflow.erp.masterdata;

import com.kflow.erp.masterdata.api.*;
import com.kflow.erp.masterdata.domain.ItemValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

class ItemValuesTest {
    @Test void canonicalCodeAndUnicodeTextBoundaries() {
        assertThat(ItemValues.code(" pcb-a_2 ")).isEqualTo("PCB-A_2");
        assertThat(ItemValues.name("  품목😀  ")).isEqualTo("품목😀");
        assertThat(ItemValues.name("😀".repeat(100))).hasSize(200);
        assertThatThrownBy(() -> ItemValues.name("😀".repeat(101))).isInstanceOf(ItemFailure.class);
        assertThat(ItemValues.specification(" ")).isNull();
        assertThat(ItemValues.price(null)).isNull();
        assertThat(ItemValues.price(new BigDecimal("0.000"))).isEqualTo(BigDecimal.ZERO);
        assertThat(ItemValues.price(new BigDecimal("999999999999"))).isEqualByComparingTo("999999999999");
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"-ABC", "A B", "é", "ＡＢＣ", "\tABC", "ABC\n", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void invalidCodes(String value) { assertThatThrownBy(() -> ItemValues.code(value)).isInstanceOf(ItemFailure.class); }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ", "\t", "\u00a0", "\u2003", "x\n", "x\u0085", "\ud800"})
    void invalidNames(String value) { assertThatThrownBy(() -> ItemValues.name(value)).isInstanceOf(ItemFailure.class); }
    @ParameterizedTest @ValueSource(strings={"-1", "0.1", "999999999999.1", "1000000000000"})
    void exactIntegerReferencePriceOnly(String value) { assertThatThrownBy(() -> ItemValues.price(new BigDecimal(value))).isInstanceOf(ItemFailure.class); }
    @ParameterizedTest @EnumSource(Unit.class)
    void unitQuantityNeverRoundsOrConverts(Unit unit) {
        ItemValues.quantity(unit, new BigDecimal("1.0000"));
        ItemValues.quantity(unit, new BigDecimal(unit == Unit.KG ? "0.001" : "1"));
        for (String invalid : new String[]{"0", "-1", unit == Unit.KG ? "0.0001" : "1.5"})
            assertThatThrownBy(() -> ItemValues.quantity(unit, new BigDecimal(invalid))).isInstanceOf(ItemFailure.class);
    }
    @ParameterizedTest @CsvSource({"RAW_MATERIAL,PURCHASED_MATERIAL", "MERCHANDISE,RESALE", "FINISHED_GOOD,MANUFACTURED_FINISHED"})
    void onlyInitialProfiles(String category, String profile) {
        ItemValues.profile(category, profile, true);
        assertThatThrownBy(() -> ItemValues.profile(category, profile, false)).isInstanceOf(ItemFailure.class);
        assertThatThrownBy(() -> ItemValues.profile(category, "INTERNAL_USE", true)).isInstanceOf(ItemFailure.class);
        assertThatThrownBy(() -> ItemValues.profile("CONSUMABLE", profile, true)).isInstanceOf(ItemFailure.class);
    }
}
