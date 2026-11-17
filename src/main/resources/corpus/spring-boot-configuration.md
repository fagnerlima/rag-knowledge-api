# Spring Boot externalized configuration

Spring Boot lets the same application artifact run in different environments by reading configuration from outside the code. Values come from many property sources, merged in a fixed order, and are bound to the application through `@Value` or, preferably, through typed `@ConfigurationProperties` classes.

## Property sources and precedence

Higher sources override lower ones. From lowest to highest, the most common sources are: default properties set in code, `application.properties` or `application.yaml` packaged in the jar, profile-specific files such as `application-prod.properties`, files outside the jar in the working directory or in a `config/` folder, operating system environment variables, Java system properties (`-Dname=value`) and command-line arguments (`--name=value`). Test annotations such as `@SpringBootTest(properties = ...)` and `@DynamicPropertySource` sit above them all, which is how tests point an application at a throwaway database.

## Relaxed binding

Spring Boot matches property names loosely, so one setting can be written in the style each source expects. The property `spring.datasource.url` can be set as the environment variable `SPRING_DATASOURCE_URL`: dots become underscores, dashes are removed and the name is upper-cased. Inside property files, the canonical form uses lowercase kebab case, such as `server.max-http-request-header-size`.

## Placeholders and defaults

Property values can reference other properties or environment variables with `${...}` placeholders and provide a default after a colon, for example `spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5432/app}`. This keeps a sensible local default in the packaged file while letting each environment override it, and it documents which variables the application reads.

## Profiles

Profiles group configuration for an environment or a feature. They are activated with `spring.profiles.active`, often set through the `SPRING_PROFILES_ACTIVE` environment variable, and load the matching profile-specific files on top of the defaults. Beans can also be limited to a profile with `@Profile`. Profiles work best for a small number of real differences; when every environment needs a different value, an environment variable is simpler than a new profile.

## Typed configuration properties

A record or class annotated with `@ConfigurationProperties(prefix = "app.ingestion")` binds every property under that prefix to typed fields, including durations such as `30s`, data sizes such as `10MB`, lists and nested objects. Adding `@Validated` with Bean Validation annotations makes the application refuse to start when a value is missing or out of range, which is better than failing on the first request. The configuration processor can generate metadata so that IDEs autocomplete and document the custom properties.

## Secrets

Credentials should not be committed in property files. Common options are environment variables injected by the platform, files mounted from a secret store and imported with `spring.config.import`, or a dedicated integration with a secret manager. Whatever the source, avoid logging resolved values: actuator endpoints such as `/actuator/env` sanitize keys that look like passwords or tokens, but custom property names may not match those patterns.

## Inspecting the result

When a value is not what you expect, the `/actuator/env` and `/actuator/configprops` endpoints show which source won for each property, and starting the application with `--debug` prints the auto-configuration report explaining which configuration classes were applied and why.
