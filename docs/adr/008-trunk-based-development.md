# ADR-008: Trunk-based development with short-lived branches

**Status:** Accepted
**Date:** 2026-10-14
**Supersedes / relates to:** —

## Context

With one developer, long-lived branches have no upside and a real cost: the longer they live the
more the merge becomes the hard part. But committing straight to `main` leaves no reviewable
artefact, and part of the point of this project is a visible delivery trail.

## Decision

`main` is always deployable and branch-protected. Short-lived branches, ideally under two days,
named `feat/SL-203-void-expense`. One story per pull request, Conventional Commits, squash merge,
self-review of the diff before merging. CI must be green to merge. Tag `v0.x.0` at each sprint end.
Dependabot weekly for Maven, npm, Docker and Actions.

## Alternatives considered

- **git-flow.** Pros: familiar, well documented. Cons: `develop` plus release branches is pure
  overhead for one person deploying continuously.
- **Commit directly to `main`.** Pros: fastest. Cons: no PR to show, no gate before `main`, and no
  forced pause to read your own diff — which is the only code review available here.

## Consequences

- **Positive:** every change carries a reviewable diff and a green pipeline. `main` is always a
  deployable commit, which is the precondition that makes deploy-on-merge safe.
- **Negative:** requires the discipline to keep stories small enough to land in two days; the
  Definition of Ready's 5-point ceiling exists to support this. Squash merge discards intermediate
  commits, which the PR retains anyway.
