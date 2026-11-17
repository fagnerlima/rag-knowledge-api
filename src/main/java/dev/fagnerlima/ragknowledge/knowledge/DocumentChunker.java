package dev.fagnerlima.ragknowledge.knowledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

/**
 * Splits a corpus document into chunks of about {@value #CHUNK_SIZE_TOKENS} tokens, each with a deterministic id and
 * the metadata returned with search results. Deterministic ids make a re-ingestion overwrite chunks instead of copying.
 */
@Component
public class DocumentChunker {

    static final int CHUNK_SIZE_TOKENS = 400;

    static final String DOCUMENT_ID = "documentId";
    static final String TITLE = "title";
    static final String SOURCE = "source";
    static final String CHUNK_INDEX = "chunkIndex";
    static final String CONTENT_HASH = "contentHash";

    // Far above any note of the corpus. The splitter does not stop at its own limit (it appends the rest of the text as
    // one oversized chunk), so the limit is enforced here.
    static final int MAX_CHUNKS_PER_DOCUMENT = 100;

    // The library keeps only pieces longer than this (5 by default): a tiny document would get no chunk at all, never
    // be recorded as ingested and be "added" again on every run. Zero keeps every non-blank piece.
    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 0;

    // Part of the content hash, so changing how documents are split re-ingests them instead of leaving old chunks.
    private static final String CHUNKING_SETTINGS = "token-text-splitter;chunk-size=" + CHUNK_SIZE_TOKENS
            + ";min-length=" + MIN_CHUNK_LENGTH_TO_EMBED;

    private final TokenTextSplitter splitter = TokenTextSplitter.builder()
            .withChunkSize(CHUNK_SIZE_TOKENS)
            .withMinChunkLengthToEmbed(MIN_CHUNK_LENGTH_TO_EMBED)
            .withMaxNumChunks(MAX_CHUNKS_PER_DOCUMENT)
            .build();

    public List<Document> chunk(CorpusDocument document) {
        List<Document> pieces = splitter.split(new Document(document.text()));

        if (pieces.size() > MAX_CHUNKS_PER_DOCUMENT) {
            throw new IllegalStateException("Corpus document " + document.id() + " is too long: more than "
                    + MAX_CHUNKS_PER_DOCUMENT + " chunks of " + CHUNK_SIZE_TOKENS + " tokens");
        }

        List<Document> chunks = new ArrayList<>(pieces.size());
        String contentHash = contentHash(document);

        for (int index = 0; index < pieces.size(); index++) {
            chunks.add(Document.builder()
                    .id(chunkId(document.id(), index))
                    .text(pieces.get(index).getText())
                    .metadata(Map.of(
                            DOCUMENT_ID, document.id(),
                            TITLE, document.title(),
                            SOURCE, document.source(),
                            CHUNK_INDEX, index,
                            CONTENT_HASH, contentHash))
                    .build());
        }

        return chunks;
    }

    /**
     * SHA-256 of everything that shapes the stored chunks: the chunking settings, title, source file and text. A
     * change in any of them means re-ingesting the document.
     */
    public static String contentHash(CorpusDocument document) {
        String content = String.join("\n", CHUNKING_SETTINGS, document.title(), document.source(), document.text());

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", exception);
        }
    }

    static String chunkId(String documentId, int chunkIndex) {
        return UUID.nameUUIDFromBytes((documentId + "#" + chunkIndex).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
