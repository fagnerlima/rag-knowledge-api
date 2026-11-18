package dev.fagnerlima.ragknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetrievedPassageTest {

    @Test
    void excerptKeepsAShortTextWholeOnOneLine() {
        assertThat(passage("  First line.\n\n  Second\tline.  ").excerpt(40)).isEqualTo("First line. Second line.");
    }

    @Test
    void excerptOfATextExactlyAtTheLimitHasNoEllipsis() {
        assertThat(passage("abcde").excerpt(5)).isEqualTo("abcde");
    }

    @Test
    void excerptCutsALongTextAtTheLimitAndAddsAnEllipsis() {
        assertThat(passage("abcde fghij").excerpt(5)).isEqualTo("abcde…");
    }

    @Test
    void excerptKeepsACutAtASpaceAsIsLikeTheSearchCommandAlwaysDid() {
        assertThat(passage("abcde fghij").excerpt(6)).isEqualTo("abcde …");
    }

    private static RetrievedPassage passage(String text) {
        return new RetrievedPassage("id", "Title", "file.md", 0, text, 0.5);
    }
}
