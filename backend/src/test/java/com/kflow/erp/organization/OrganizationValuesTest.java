package com.kflow.erp.organization;

import com.kflow.erp.organization.domain.OrganizationValues;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import com.kflow.erp.organization.api.OrganizationFailure;

class OrganizationValuesTest {
    @ParameterizedTest @CsvSource({"hq,HQ", "Hq,HQ", "HQ,HQ", "'  hq  ',HQ", "hwaseong-01,HWASEONG-01", "HQ-1,HQ-1", "HQ_1,HQ_1", "001,001"})
    void canonicalCode(String input, String expected) { assertThat(OrganizationValues.code(input)).isEqualTo(expected); }
    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {" ", "H Q", "-HQ", "_HQ", "HQ.1", "\tHQ", "HQ\n", "HQ\r", "\u00a0HQ", "ＨＱ", "한글", "İ", "ß", "123456789012345678901234567890123"})
    void invalidCodes(String input) { assertThatThrownBy(() -> OrganizationValues.code(input)).isInstanceOf(OrganizationFailure.class); }
    @Test void codeLengthBoundary() { assertThat(OrganizationValues.code("a".repeat(32))).isEqualTo("A".repeat(32)); }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ", "\t", "\u00a0", "이름\n", "이름\u0000", "\uD800"})
    void invalidNames(String name) { assertThatThrownBy(() -> OrganizationValues.name(name)).isInstanceOf(OrganizationFailure.class); }
    @Test void unicodeNamesAndCodePointLength() {
        assertThat(OrganizationValues.name("  화성 생산  ")).isEqualTo("화성 생산");
        assertThat(OrganizationValues.name("😀".repeat(100))).isEqualTo("😀".repeat(100));
        assertThatThrownBy(() -> OrganizationValues.name("😀".repeat(101))).isInstanceOf(OrganizationFailure.class);
    }
}
