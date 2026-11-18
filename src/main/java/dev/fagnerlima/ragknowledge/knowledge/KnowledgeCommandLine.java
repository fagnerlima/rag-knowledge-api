package dev.fagnerlima.ragknowledge.knowledge;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.stereotype.Component;

/**
 * The {@value #INGEST} and {@value #SEARCH}{@code =<query>} command-line options. They are deliberately not HTTP
 * endpoints: the API has no authentication, and ingestion is a bulk, billed write. With either option the
 * application runs without the web server and exits when done (see {@code RagKnowledgeApiApplication}).
 */
@Component
public class KnowledgeCommandLine implements ApplicationRunner {

    public static final String INGEST = "ingest";
    public static final String SEARCH = "search";

    static final int SEARCH_TOP_K = 4;

    private static final int EXCERPT_LENGTH = 160;

    private final CorpusLoader corpusLoader;
    private final DocumentIngestionService ingestionService;
    private final PassageRetriever retriever;

    public KnowledgeCommandLine(CorpusLoader corpusLoader, DocumentIngestionService ingestionService,
            PassageRetriever retriever) {
        this.corpusLoader = corpusLoader;
        this.ingestionService = ingestionService;
        this.retriever = retriever;
    }

    /** Whether the arguments ask for a knowledge command instead of serving the API. */
    // Same parser as run(), so every form that triggers a command (such as --ingest=false) also disables the server.
    public static boolean isCommand(String... args) {
        ApplicationArguments arguments = new DefaultApplicationArguments(args);

        return arguments.containsOption(INGEST) || arguments.containsOption(SEARCH);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (args.containsOption(INGEST)) {
            if (!args.getOptionValues(INGEST).isEmpty()) {
                throw new IllegalArgumentException("Use --ingest without a value");
            }

            ingest();
        }

        if (args.containsOption(SEARCH)) {
            search(singleQuery(args.getOptionValues(SEARCH)));
        }
    }

    private void ingest() {
        IngestionReport report = ingestionService.ingest(corpusLoader.load());

        System.out.printf("Ingestion finished: %d added %s, %d updated %s, %d unchanged, %d removed %s; "
                        + "%d chunks embedded%n",
                report.added().size(), report.added(), report.updated().size(), report.updated(),
                report.unchanged().size(), report.removed().size(), report.removed(), report.embeddedChunks());
    }

    private void search(String query) {
        List<RetrievedPassage> results = retriever.retrieveTop(query, SEARCH_TOP_K);

        System.out.printf("Top %d chunks for \"%s\":%n", results.size(), query);

        for (RetrievedPassage result : results) {
            System.out.printf(Locale.ROOT, "%.3f  %s (chunk %d, %s)%n       %s%n", result.score(), result.title(),
                    result.chunkIndex(), result.source(), result.excerpt(EXCERPT_LENGTH));
        }
    }

    private static String singleQuery(List<String> values) {
        if (values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("Use --search=<query> once, with a non-blank query");
        }

        return values.getFirst().strip();
    }
}
