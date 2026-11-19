package dev.fagnerlima.ragknowledge.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotent ingestion of the corpus into the vector store. The content hash stored in every chunk's metadata tells
 * which documents are unchanged (skipped, nothing embedded), new or changed (chunks replaced in one transaction, so a
 * failed embedding call keeps the previous version) and gone from the corpus (chunks deleted).
 *
 * <p>The corpus owns the whole table: pruning deletes every document missing from the manifest. Run one ingestion at a
 * time; two concurrent runs may embed the same documents twice and leave mixed versions until the next run.
 */
@Service
public class DocumentIngestionService {

    private static final String INDEXED_HASHES_SQL = """
            SELECT DISTINCT metadata->>'documentId' AS document_id, metadata->>'contentHash' AS content_hash
            FROM public.vector_store
            WHERE metadata->>'documentId' IS NOT NULL
            """;

    private final VectorStore vectorStore;
    private final DocumentChunker chunker;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public DocumentIngestionService(VectorStore vectorStore, DocumentChunker chunker, JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate) {
        this.vectorStore = vectorStore;
        this.chunker = chunker;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public IngestionReport ingest(List<CorpusDocument> corpus) {
        Map<String, Set<String>> indexedHashes = indexedHashesByDocument();
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        int embeddedChunks = 0;

        for (CorpusDocument document : corpus) {
            Set<String> hashes = indexedHashes.remove(document.id());

            // More than one hash means a partial earlier state; anything but exactly the current hash is re-ingested.
            if (hashes != null && hashes.equals(Set.of(DocumentChunker.contentHash(document)))) {
                unchanged.add(document.id());

                continue;
            }

            embeddedChunks += replaceChunks(document);
            (hashes == null ? added : updated).add(document.id());
        }

        List<String> removed = new ArrayList<>(indexedHashes.keySet());
        removed.sort(null);
        removed.forEach(this::deleteChunks);

        return new IngestionReport(added, updated, unchanged, removed, embeddedChunks);
    }

    private Map<String, Set<String>> indexedHashesByDocument() {
        Map<String, Set<String>> hashes = new HashMap<>();

        jdbcTemplate.query(INDEXED_HASHES_SQL, row -> {
            hashes.computeIfAbsent(row.getString("document_id"), id -> new HashSet<>())
                    .add(row.getString("content_hash"));
        });

        return hashes;
    }

    private int replaceChunks(CorpusDocument document) {
        List<Document> chunks = chunker.chunk(document);

        transactionTemplate.executeWithoutResult(status -> {
            deleteChunks(document.id());
            vectorStore.add(chunks);
        });

        return chunks.size();
    }

    private void deleteChunks(String documentId) {
        vectorStore.delete(new FilterExpressionBuilder().eq(DocumentChunker.DOCUMENT_ID, documentId).build());
    }
}
