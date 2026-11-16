package dev.fagnerlima.ragknowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import dev.fagnerlima.ragknowledge.support.PgvectorTestDatabase;

// A fake key is enough: the context only builds the OpenAI client and never calls it. Components are shown only
// here, to prove the database takes part in the health status.
@SpringBootTest(properties = {
        "spring.ai.openai.api-key=test-key-not-real",
        "spring.ai.openai.base-url=http://127.0.0.1:9",
        "management.endpoint.health.show-components=always"
})
@AutoConfigureMockMvc
@Import(PgvectorTestDatabase.class)
class RagKnowledgeApiApplicationTests {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void startsAndReportsHealthIncludingTheDatabase() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.status").isEqualTo("UP");
                    assertThat(json).extractingPath("$.components.db.status").isEqualTo("UP");
                });
    }
}
