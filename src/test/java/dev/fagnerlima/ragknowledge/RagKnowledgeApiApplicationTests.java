package dev.fagnerlima.ragknowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

// A fake key is enough: the context only builds the OpenAI client and never calls it.
@SpringBootTest(properties = "spring.ai.openai.api-key=test-key-not-real")
@AutoConfigureMockMvc
class RagKnowledgeApiApplicationTests {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void startsAndReportsHealth() {
        assertThat(mvc.get().uri("/actuator/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status").isEqualTo("UP");
    }
}
