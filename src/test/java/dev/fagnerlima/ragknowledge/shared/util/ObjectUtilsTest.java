package dev.fagnerlima.ragknowledge.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ObjectUtilsTest {

    @Test
    void detectsANullValue() {
        assertThat(ObjectUtils.anyNullOrBlank("text", null)).isTrue();
    }

    @Test
    void detectsEmptyAndBlankText() {
        assertThat(ObjectUtils.anyNullOrBlank("text", "")).isTrue();
        assertThat(ObjectUtils.anyNullOrBlank(" \t\n", 1)).isTrue();
        assertThat(ObjectUtils.anyNullOrBlank(new StringBuilder("  "))).isTrue();
    }

    @Test
    void acceptsPresentValues() {
        assertThat(ObjectUtils.anyNullOrBlank("text", 0, Boolean.FALSE)).isFalse();
    }

    @Test
    void treatsNoValuesAsNothingMissing() {
        assertThat(ObjectUtils.anyNullOrBlank()).isFalse();
    }
}
