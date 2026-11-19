package dev.fagnerlima.ragknowledge.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Closing a context that uses the shared database must not stop the container: the next context in the same JVM
 * still needs it. The first nested class closes its context when it ends; the second runs after it. A stopped
 * container would be started again by the next context as a new Docker container, losing its data, so the check
 * compares container ids rather than only querying the database.
 */
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
class PgvectorTestDatabaseLifecycleTest {

    private static String containerIdBeforeClosing;

    @Nested
    @Order(1)
    @SpringBootTest(properties = {
            "spring.ai.openai.api-key=test-key-not-real",
            "spring.ai.openai.base-url=http://127.0.0.1:9"
    })
    @Import(PgvectorTestDatabase.class)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class ContextClosedAfterThisClass {

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Test
        void queriesTheDatabase() {
            assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            containerIdBeforeClosing = PgvectorTestDatabase.containerId();
        }
    }

    @Nested
    @Order(2)
    @SpringBootTest(properties = {
            "spring.ai.openai.api-key=test-key-not-real",
            "spring.ai.openai.base-url=http://127.0.0.1:9"
    })
    @Import(PgvectorTestDatabase.class)
    class NextContext {

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Test
        void stillQueriesTheSameRunningContainer() {
            assertThat(containerIdBeforeClosing).isNotNull();
            assertThat(PgvectorTestDatabase.containerId()).isEqualTo(containerIdBeforeClosing);
            assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
        }
    }
}
