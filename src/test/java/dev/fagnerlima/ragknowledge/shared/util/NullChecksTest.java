package dev.fagnerlima.ragknowledge.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NullChecksTest {

    @Test
    void detectsANullValue() {
        assertThat(NullChecks.anyNullOrBlank("text", null)).isTrue();
    }

    @Test
    void detectsEmptyAndBlankText() {
        assertThat(NullChecks.anyNullOrBlank("text", "")).isTrue();
        assertThat(NullChecks.anyNullOrBlank(" \t\n", 1)).isTrue();
        assertThat(NullChecks.anyNullOrBlank(new StringBuilder("  "))).isTrue();
    }

    @Test
    void acceptsPresentValues() {
        assertThat(NullChecks.anyNullOrBlank("text", 0, Boolean.FALSE)).isFalse();
    }

    @Test
    void treatsNoValuesAsNothingMissing() {
        assertThat(NullChecks.anyNullOrBlank()).isFalse();
    }
}
