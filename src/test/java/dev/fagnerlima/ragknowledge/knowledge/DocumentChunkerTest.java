package dev.fagnerlima.ragknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import tools.jackson.databind.json.JsonMapper;

class DocumentChunkerTest {

    // The splitter's default encoding, which is also the one of text-embedding-3-small.
    private static final Encoding CL100K = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void aShortDocumentBecomesOneChunkWithItsMetadata() {
        CorpusDocument document = new CorpusDocument("short-note", "Short note", "short-note.md",
                "A short note about vector search.");

        List<Document> chunks = chunker.chunk(document);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.getId()).isEqualTo(DocumentChunker.chunkId("short-note", 0));
            assertThat(chunk.getText()).isEqualTo("A short note about vector search.");
            assertThat(chunk.getMetadata())
                    .containsEntry("documentId", "short-note")
                    .containsEntry("title", "Short note")
                    .containsEntry("source", "short-note.md")
                    .containsEntry("chunkIndex", 0)
                    .containsEntry("contentHash", DocumentChunker.contentHash(document));
        });
    }

    // Regression: the splitter's default dropped pieces of up to 5 characters, leaving such documents without chunks.
    @Test
    void aTinyDocumentStillBecomesOneChunk() {
        assertThat(chunker.chunk(new CorpusDocument("tiny", "Tiny", "tiny.md", "Pods.")))
                .extracting(Document::getText)
                .containsExactly("Pods.");
        assertThat(chunker.chunk(new CorpusDocument("one", "One", "one.md", "x")))
                .extracting(Document::getText)
                .containsExactly("x");
    }

    // The splitter itself would return the excess as one oversized chunk, beyond the embedding model's input limit.
    @Test
    void aDocumentAboveTheChunkLimitIsRejected() {
        String text = "Vectors, tuples and pods. ".repeat(DocumentChunker.MAX_CHUNKS_PER_DOCUMENT * 70);

        assertThatThrownBy(() -> chunker.chunk(new CorpusDocument("huge", "Huge", "huge.md", text)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Corpus document huge is too long");
    }

    @Test
    void contentHashIsAStableSha256InHex() {
        CorpusDocument document = new CorpusDocument("doc", "Title", "doc.md", "Some text.");

        assertThat(DocumentChunker.contentHash(document))
                .hasSize(64)
                .matches("[0-9a-f]+")
                .isEqualTo(DocumentChunker.contentHash(new CorpusDocument("doc", "Title", "doc.md", "Some text.")));
    }

    @Test
    void contentHashChangesWithTheTextTheTitleOrTheSourceFile() {
        String original = DocumentChunker.contentHash(new CorpusDocument("doc", "Title", "doc.md", "Some text."));

        assertThat(DocumentChunker.contentHash(new CorpusDocument("doc", "Title", "doc.md", "Other text.")))
                .isNotEqualTo(original);
        assertThat(DocumentChunker.contentHash(new CorpusDocument("doc", "Other title", "doc.md", "Some text.")))
                .isNotEqualTo(original);
        assertThat(DocumentChunker.contentHash(new CorpusDocument("doc", "Title", "moved.md", "Some text.")))
                .isNotEqualTo(original);
    }

    @Test
    void aLongDocumentBecomesNumberedChunksOfAtMostTheChunkSize() {
        String sentence = "Each sentence talks about indexes, tuples and vectors in a database. ";
        CorpusDocument document = new CorpusDocument("long-note", "Long note", "long-note.md", sentence.repeat(120));

        List<Document> chunks = chunker.chunk(document);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).extracting(chunk -> chunk.getMetadata().get("chunkIndex"))
                .containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
        assertThat(chunks).extracting(Document::getId)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(IntStream.range(0, chunks.size())
                        .mapToObj(index -> DocumentChunker.chunkId("long-note", index)).toList());
        assertThat(chunks).allSatisfy(chunk -> assertThat(CL100K.countTokens(chunk.getText()))
                .isLessThanOrEqualTo(DocumentChunker.CHUNK_SIZE_TOKENS));
    }

    @Test
    void chunkingIsDeterministic() {
        CorpusDocument document = new CorpusDocument("doc", "Doc", "doc.md", "Vectors and tuples. ".repeat(300));

        assertThat(chunker.chunk(document)).isEqualTo(chunker.chunk(document));
    }

    @Test
    void chunkIdsDependOnTheDocumentAndTheIndex() {
        assertThat(DocumentChunker.chunkId("doc", 0))
                .isEqualTo(DocumentChunker.chunkId("doc", 0))
                .isNotEqualTo(DocumentChunker.chunkId("doc", 1))
                .isNotEqualTo(DocumentChunker.chunkId("other", 0));
    }

    @Test
    void everyBundledNoteIsSplitIntoSeveralChunks() {
        List<CorpusDocument> corpus = new CorpusLoader(new DefaultResourceLoader(), JsonMapper.builder().build(),
                "classpath:corpus/manifest.json").load();

        assertThat(corpus).allSatisfy(document -> assertThat(chunker.chunk(document)).hasSizeBetween(2, 4));
    }
}
