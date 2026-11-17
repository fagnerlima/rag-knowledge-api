package dev.fagnerlima.ragknowledge.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import dev.fagnerlima.ragknowledge.shared.util.ObjectUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the corpus manifest ({@code documents: [{id, title, file}]}) and each document's text, resolving files relative
 * to the manifest. Rejects the whole corpus when an entry is invalid, so a typo never prunes indexed documents.
 */
@Component
public class CorpusLoader {

    // Ids end up in chunk ids and in metadata filter expressions: keep them to a safe, readable alphabet.
    private static final Pattern DOCUMENT_ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private final ResourceLoader resourceLoader;
    private final JsonMapper jsonMapper;
    private final String manifestLocation;

    public CorpusLoader(ResourceLoader resourceLoader, JsonMapper jsonMapper,
            @Value("${knowledge.corpus.manifest:classpath:corpus/manifest.json}") String manifestLocation) {
        this.resourceLoader = resourceLoader;
        this.jsonMapper = jsonMapper;
        this.manifestLocation = manifestLocation;
    }

    public List<CorpusDocument> load() {
        Resource manifest = resourceLoader.getResource(manifestLocation);
        Manifest parsed = readManifest(manifest);

        if (parsed.documents() == null || parsed.documents().isEmpty()) {
            throw new IllegalStateException("The corpus manifest lists no documents: " + manifestLocation);
        }

        List<CorpusDocument> documents = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        for (Entry entry : parsed.documents()) {
            validate(entry, ids);
            documents.add(new CorpusDocument(entry.id(), entry.title(), entry.file(), readText(manifest, entry)));
        }

        return List.copyOf(documents);
    }

    private Manifest readManifest(Resource manifest) {
        try (InputStream in = manifest.getInputStream()) {
            return jsonMapper.readValue(in, Manifest.class);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read the corpus manifest " + manifestLocation, exception);
        }
    }

    private static void validate(Entry entry, Set<String> ids) {
        if (entry == null || ObjectUtils.anyNullOrBlank(entry.id(), entry.title(), entry.file())) {
            throw new IllegalStateException("Every corpus entry needs an id, a title and a file: " + entry);
        }

        if (!DOCUMENT_ID.matcher(entry.id()).matches()) {
            throw new IllegalStateException("Invalid corpus document id (use lowercase letters, digits and dashes): "
                    + entry.id());
        }

        if (!ids.add(entry.id())) {
            throw new IllegalStateException("Duplicate corpus document id: " + entry.id());
        }
    }

    private static String readText(Resource manifest, Entry entry) {
        try (InputStream in = manifest.createRelative(entry.file()).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            if (text.isBlank()) {
                throw new IllegalStateException("Corpus document " + entry.id() + " is empty: " + entry.file());
            }

            return text;
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read corpus document " + entry.id() + ": " + entry.file(), exception);
        }
    }

    record Manifest(List<Entry> documents) {
    }

    record Entry(String id, String title, String file) {
    }
}
