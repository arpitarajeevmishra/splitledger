# ADR-016: Java 27 and Spring Boot 4.1 as the baseline

**Status:** Accepted
**Date:** 2026-10-15
**Supersedes / relates to:** ADR-012

## Context

Every document written before implementation assumed **Java 21** and **Spring Boot 3.x**. Neither
survived contact with the machine.

The development machine had no JDK at all. The one that got installed was **Oracle JDK 27**, and
the alternative was installing a second JDK alongside it purely to match the documents. Separately,
Spring Initializr's current output for Java 27 is **Spring Boot 4.1.1** — 3.x is no longer what a
new project gets.

So the question was not "which versions would we prefer" but "do we add a second JDK to match the
documents, or move the documents to what is installed".

## Decision

Baseline is **Java 27** and **Spring Boot 4.1.1**, and CI is pinned to the same Java version so
there is no local/CI drift.

The decision was taken **empirically rather than on faith**: the Initializr was queried for
supported Java versions (27 is offered), Maven Central was queried for the real artifact
coordinates, and the project was compiled and tested before any further code was written. The
toolchain works — `./mvnw package` produces a jar, `./mvnw test` passes.

## Alternatives considered

- **Install Temurin 21 alongside and pin the project to it.** Pros: LTS, matches Spring Boot's
  tested baseline, matches every document already written. Cons: a second JDK on the machine for no
  functional gain. This was the recommended option; the user chose otherwise, with the trade-offs
  below stated first.
- **Keep Spring Boot 3.x on Java 27.** Not coherent: Boot 3.x predates Java 27 and is not tested
  against it, so this would combine the risk of a new JVM with an older framework that has not seen
  it.
- **Target release 21 bytecode while running javac 27.** Would hide the mismatch rather than
  resolve it, and the libraries that break on new class-file versions (ByteBuddy, Mockito, JaCoCo)
  break on the *runtime* version, not the target.

## Consequences

- **Positive:** nothing extra to install; one JDK on the machine and the same version in CI. The
  current Spring Boot generation also removes some of the configuration the LLD anticipated.
- **Negative — the real cost.** Java 27 is a **non-LTS interim release** with roughly six months
  of support, where 21 and 25 are LTS; a project meant to stay live will need to move. Spring Boot
  4.x has far less community material than 3.x, so answers come from reference documentation rather
  than Stack Overflow. Bytecode-manipulating tooling lags new class-file versions, which is part of
  why JaCoCo is deferred (see `decision.md` D-09).
- **Knock-on changes already required:** starters renamed (`spring-boot-starter-flyway`,
  `spring-boot-starter-kafka`), springdoc moves to the **3.x** line, Spring Security 7 is
  lambda-DSL only, and `org.testcontainers.postgresql.PostgreSQLContainer` is no longer generic.
- **Follow-up:** revisit at the next LTS. Moving to 25 or 29 is a `java.version` property change
  plus a CI bump, so the cost of this decision is bounded and deferred, not avoided.
