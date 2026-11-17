package dev.fagnerlima.ragknowledge.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import tools.jackson.databind.json.JsonMapper;

class CorpusLoaderTest {

    @TempDir
    private Path corpusDirectory;

    @Test
    void loadsTheBundledCorpusOfOriginalNotes() {
        List<CorpusDocument> corpus = loaderFor("classpath:corpus/manifest.json").load();

        assertThat(corpus).extracting(CorpusDocument::id).containsExactly(
                "pgvector-hnsw", "postgresql-mvcc", "kubernetes-pods", "twelve-factor", "spring-boot-configuration");
        assertThat(corpus).allSatisfy(document -> {
            assertThat(document.title()).isNotBlank();
            assertThat(document.source()).endsWith(".md");
            assertThat(document.text()).startsWith("# ");
        });
    }

    @Test
    void readsEachDocumentRelativeToTheManifest() throws IOException {
        Files.writeString(corpusDirectory.resolve("notes.md"), "# Notes\n\nBody.");
        writeManifest("""
                {"documents": [{"id": "notes", "title": "Notes", "file": "notes.md"}]}
                """);

        assertThat(load()).containsExactly(new CorpusDocument("notes", "Notes", "notes.md", "# Notes\n\nBody."));
    }

    @Test
    void rejectsAManifestWithoutDocuments() throws IOException {
        writeManifest("""
                {"documents": []}
                """);

        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class).hasMessageContaining("no documents");
    }

    @Test
    void rejectsAnEntryWithoutTitle() throws IOException {
        Files.writeString(corpusDirectory.resolve("notes.md"), "Body.");
        writeManifest("""
                {"documents": [{"id": "notes", "file": "notes.md"}]}
                """);

        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needs an id, a title and a file");
    }

    @Test
    void rejectsAnIdOutsideTheSafeAlphabet() throws IOException {
        Files.writeString(corpusDirectory.resolve("notes.md"), "Body.");
        writeManifest("""
                {"documents": [{"id": "Notes' || true", "title": "Notes", "file": "notes.md"}]}
                """);

        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid corpus document id");
    }

    @Test
    void rejectsDuplicateIds() throws IOException {
        Files.writeString(corpusDirectory.resolve("notes.md"), "Body.");
        writeManifest("""
                {"documents": [
                  {"id": "notes", "title": "Notes", "file": "notes.md"},
                  {"id": "notes", "title": "Again", "file": "notes.md"}
                ]}
                """);

        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate corpus document id: notes");
    }

    @Test
    void rejectsAMissingFile() throws IOException {
        writeManifest("""
                {"documents": [{"id": "notes", "title": "Notes", "file": "missing.md"}]}
                """);

        assertThatThrownBy(this::load).isInstanceOf(UncheckedIOException.class).hasMessageContaining("missing.md");
    }

    @Test
    void rejectsABlankFile() throws IOException {
        Files.writeString(corpusDirectory.resolve("notes.md"), " \n ");
        writeManifest("""
                {"documents": [{"id": "notes", "title": "Notes", "file": "notes.md"}]}
                """);

        assertThatThrownBy(this::load).isInstanceOf(IllegalStateException.class).hasMessageContaining("is empty");
    }

    private void writeManifest(String json) throws IOException {
        Files.writeString(corpusDirectory.resolve("manifest.json"), json);
    }

    private List<CorpusDocument> load() {
        return loaderFor(corpusDirectory.resolve("manifest.json").toUri().toString()).load();
    }

    private static CorpusLoader loaderFor(String manifestLocation) {
        return new CorpusLoader(new DefaultResourceLoader(), JsonMapper.builder().build(), manifestLocation);
    }
}
