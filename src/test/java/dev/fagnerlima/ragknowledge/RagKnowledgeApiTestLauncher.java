package dev.fagnerlima.ragknowledge;

import org.springframework.context.ConfigurableApplicationContext;

import dev.fagnerlima.ragknowledge.support.PgvectorTestDatabase;

/** Starts the application the way main does, plus the test database configuration (fake embedding model). */
public final class RagKnowledgeApiTestLauncher {

    private RagKnowledgeApiTestLauncher() {
    }

    public static ConfigurableApplicationContext run(String... args) {
        return RagKnowledgeApiApplication.application(args).sources(PgvectorTestDatabase.class).run(args);
    }
}
