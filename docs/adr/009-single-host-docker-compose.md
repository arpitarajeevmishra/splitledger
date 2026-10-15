# ADR-009: A single host with Docker Compose for deployment

**Status:** Accepted
**Date:** 2026-10-14
**Supersedes / relates to:** ADR-012, ADR-013, ADR-015

## Context

The app has to be publicly reachable, cheaply, and the deployment itself is part of what the
project demonstrates. Managed container orchestration adds real cost and a lot of configuration
that is not the interesting part of this project.

## Decision

One always-on host running the API, the SPA behind nginx, and Caddy for automatic TLS, from
`docker-compose.prod.yml`. Images are built in CI and pushed to GHCR tagged with the commit SHA;
`latest` is a convenience only. Deployment is an SSH script that pulls the new tag, brings the
stack up, runs a smoke test, and **automatically rolls back to the previous tag** if the smoke test
fails. PostgreSQL is managed and lives **off** this host (ADR-012, ADR-013), so the host is
stateless.

## Alternatives considered

- **ECS Fargate + RDS + ALB, provisioned with Terraform.** Pros: the realistic production
  answer, strong resume surface. Cons: materially more cost and a great deal of configuration for
  one small application. Retained as an explicit stretch goal.
- **A PaaS (Fly.io, Render, Railway).** Pros: removes OS patching and the Docker daemon from your
  responsibilities entirely. Cons: less to demonstrate about Linux and containers. Remains an easy
  switch precisely because the host holds no state.

## Consequences

- **Positive:** cheap, legible, and recoverable by redeploying. Immutable SHA-tagged images make
  rollback a redeploy of a known-good tag rather than a restore.
- **Negative:** you own OS patching and the container runtime. A single host is a single point of
  failure — stated in the availability NFR rather than hidden.
- **Follow-up:** because the host is stateless, moving to a PaaS later is a configuration change,
  not a migration.
