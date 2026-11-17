package dev.fagnerlima.ragknowledge.knowledge;

import java.util.List;

/** What one ingestion run changed, by document id, and how many chunks it embedded and stored. */
public record IngestionReport(List<String> added, List<String> updated, List<String> unchanged, List<String> removed,
        int embeddedChunks) {

    public IngestionReport {
        added = List.copyOf(added);
        updated = List.copyOf(updated);
        unchanged = List.copyOf(unchanged);
        removed = List.copyOf(removed);
    }
}
