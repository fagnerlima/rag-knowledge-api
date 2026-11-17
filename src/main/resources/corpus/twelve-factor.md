# The Twelve-Factor App in practice

The Twelve-Factor App is a set of principles for building services that are easy to deploy, scale and operate on modern platforms. It was written for software-as-a-service applications, but most of its ideas apply to any service that runs in containers or on a cloud platform. These notes summarise the factors that matter most day to day.

## One codebase, explicit dependencies

Each service has one codebase tracked in version control and many deploys of it: development, staging and production run the same code at different versions. Dependencies are declared explicitly and isolated, for example with a Maven or Gradle build and a lockfile, so a fresh checkout can be built without relying on tools that happen to be installed on the machine.

## Configuration in the environment

Configuration is everything that changes between deploys: database URLs, credentials, feature switches and limits. It lives in the environment, not in the code, and a good test is whether the codebase could be made public without exposing any credential. Environment variables are the classic mechanism; secret managers and mounted files are common today, as long as the code reads them at run time and the same build artifact runs everywhere.

## Backing services as attached resources

Databases, queues, caches and external APIs are attached resources, reached through a URL or credentials kept in configuration. Swapping a local PostgreSQL for a managed one should need only a configuration change, not a code change.

## Build, release, run

The build stage turns code into an executable artifact. The release stage combines that artifact with a deploy's configuration and gets an identifier, so a release can be rolled back. The run stage executes the release. Code is never changed in a running environment; every change goes through a new build and release.

## Stateless processes and disposability

The application runs as one or more stateless processes. Anything that must persist goes to a backing service; local memory and disk are only caches. Processes start quickly and shut down gracefully when they receive a termination signal, finishing or returning in-flight work, which makes scaling, rolling updates and crash recovery safe. Scaling happens by running more processes of each type, such as web processes and workers, rather than by making one process bigger.

## Port binding and logs

A service is self-contained and exports HTTP by binding to a port; no external application server is injected at run time. Logs are treated as an event stream written to standard output; the platform collects, routes and stores them, so the application does not manage log files or rotation.

## Dev and prod parity, admin processes

Development, staging and production should be as similar as possible: the same backing services, the same versions and short gaps between writing code and deploying it. Running the real database locally in a container, instead of a lighter substitute, catches problems earlier. One-off administrative tasks, such as migrations or data fixes, run as processes of the same release and configuration as the application, not as ad hoc scripts on a server.

## Where the factors fall short

The original text predates containers and orchestration, and some advice has evolved. Secrets are better kept in a secret manager than in plain environment variables visible to every process, and observability today includes metrics and traces in addition to logs. The principles still provide a useful checklist when reviewing a new service.
