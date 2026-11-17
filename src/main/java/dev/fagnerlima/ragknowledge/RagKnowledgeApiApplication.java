package dev.fagnerlima.ragknowledge;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import dev.fagnerlima.ragknowledge.knowledge.KnowledgeCommandLine;

@SpringBootApplication
public class RagKnowledgeApiApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = application(args).run(args);

		// The command already ran as an ApplicationRunner during startup; closing the context ends the process.
		if (KnowledgeCommandLine.isCommand(args)) {
			context.close();
		}
	}

	/** Serves the API, or runs a knowledge command (--ingest, --search) without starting the web server. */
	static SpringApplicationBuilder application(String... args) {
		WebApplicationType type = KnowledgeCommandLine.isCommand(args) ? WebApplicationType.NONE
				: WebApplicationType.SERVLET;

		return new SpringApplicationBuilder(RagKnowledgeApiApplication.class).web(type);
	}

}
