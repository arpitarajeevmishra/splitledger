# Agile Process (Solo Scrum + Kanban)

You are the Product Owner, Scrum Master and Developer. The point is not ceremony for its own sake. It is to keep scope small, make progress visible, and produce an artefact trail (board, sprints, retros, ADRs) that you can show in an interview.

---

## 1. Framework at a glance

| Element | Choice |
|---|---|
| Method | Scrum with a Kanban board |
| Sprint length | 1 week (Sprint 0 is 3 to 4 days) |
| Capacity | About 15 focused hours per week, roughly 13 story points. Re-calibrate after Sprint 1. |
| Tooling | GitHub Projects (board), Issues (stories), Milestones (sprints), Actions (CI) |
| Estimation | Fibonacci story points (1, 2, 3, 5). Anything larger than 5 is split. |
| Release cadence | Deploy on every merge to `main`; tag at sprint end |

---

## 2. Roles (the three hats)

| Hat | Responsibility | When you wear it |
|---|---|---|
| Product Owner | Backlog order, acceptance criteria, scope decisions | Planning and review |
| Scrum Master | Protect the sprint goal, remove blockers, run retros | Daily and retro |
| Developer | Build, test, deploy | Everything else |

When you feel the urge to add scope mid-sprint, put on the Product Owner hat and write a backlog item instead.

---

## 3. Cadence and ceremonies

| Ceremony | Timebox | When | Output |
|---|---|---|---|
| Sprint planning | 45 min | Start of sprint | Sprint goal, selected stories, tasks |
| Daily stand-up (written) | 5 min | Each work session | One entry in the daily log |
| Backlog refinement | 30 min | Mid-sprint | Next sprint's stories are Ready |
| Sprint review (demo) | 30 min | End of sprint | Demo recording (Loom or GIF), release tag |
| Retrospective | 30 min | End of sprint | 1 to 3 concrete actions |

### Sprint calendar (weekly example)
- **Mon:** planning, start first story.
- **Tue to Fri:** build; keep the board current.
- **Sat or Sun:** finish, deploy, demo recording, review, retro, refinement of the next sprint.

---

## 4. Board

### Columns and WIP limits

| Column | Meaning | WIP |
|---|---|---|
| Backlog | Ideas and unrefined stories | none |
| Ready | Meets Definition of Ready | none |
| In Progress | Actively being built | **2** |
| In Review | PR open, CI running, self-review | 2 |
| Done | Meets Definition of Done | none |

### Labels
`epic:platform`, `epic:identity`, `epic:groups`, `epic:expenses`, `epic:ledger`, `epic:settlements`, `epic:payments`, `epic:events`, `epic:performance`, `epic:frontend`, `epic:deploy`, `type:story`, `type:bug`, `type:spike`, `type:chore`, `priority:must`, `priority:should`, `priority:could`.

### Milestones
One per sprint: `S0 Foundations`, `S1 Identity & Groups`, and so on, matching `ROADMAP.md`.

---

## 5. Backlog structure

```
Epic  →  User story  →  Tasks
E3 Expenses → "As a member I can split an expense by percentage" → [DTO, strategy, tests, UI]
```

### Story format
> As a **<role>**, I want **<capability>**, so that **<benefit>**.

Acceptance criteria use Given / When / Then:
> **Given** a group with three members, **when** I add a ₹100 equal-split expense, **then** the splits are 33.33, 33.33 and 33.34 and sum to ₹100.00.

### Prioritisation (MoSCoW)
- **Must:** needed for the MVP cut line (Phases 0 to 4 plus deployment).
- **Should:** events, caching, load test, observability.
- **Could:** stretch backlog (AI draft, UPI QR, Terraform).
- **Won't (this version):** real money movement, native apps, multi-currency.

---

## 6. Definition of Ready (DoR)

A story can enter a sprint only if:
- [ ] It follows the story format and has clear acceptance criteria.
- [ ] It is estimated at 5 points or fewer.
- [ ] Dependencies are done or are in the same sprint.
- [ ] The design question is answered (link to HLD/LLD section or an ADR).
- [ ] Test approach is noted (unit, integration, e2e).

## 7. Definition of Done (DoD)

A story is Done only if:
- [ ] Acceptance criteria are met and demonstrated.
- [ ] Unit and integration tests are written and pass; coverage gate holds.
- [ ] CI is green (build, lint, tests, security scan).
- [ ] No new HIGH/CRITICAL vulnerabilities.
- [ ] API changes are reflected in OpenAPI/Swagger.
- [ ] Docs updated if behaviour or design changed (README, LLD, ADR).
- [ ] Merged to `main` and deployed (from Phase 6 onwards; before that, runs in local Docker).
- [ ] No leftover TODOs without a linked issue.

## 8. Definition of Done: sprint level
- [ ] Sprint goal met or explicitly renegotiated.
- [ ] Increment is demoable end-to-end.
- [ ] Release tagged, changelog written.
- [ ] Demo recorded, retro written, next sprint refined.

---

## 9. Estimation and metrics

| Metric | How | Use |
|---|---|---|
| Velocity | Points completed per sprint | Plan next sprint's capacity |
| Burndown | Points remaining per day | Spot slippage early |
| Cycle time | Ready → Done per story | Detect oversized stories |
| Escaped bugs | Bugs found after Done | Tighten the DoD |

Keep a simple table in `docs/sprints/velocity.md`:

| Sprint | Planned pts | Done pts | Notes |
|---|---|---|---|
| S0 | 6 | | |
| S1 | 14 | | |

**Rules**
- Do not pull in new stories mid-sprint unless you drop something of equal size.
- Unfinished stories return to the backlog and are re-estimated; they do not automatically roll over.
- If velocity is consistently below plan, reduce planned scope; do not extend hours.

---

## 10. Templates

### 10.1 User story issue `.github/ISSUE_TEMPLATE/story.md`
```markdown
---
name: User story
about: A unit of user-visible work
labels: type:story
---
**Story**
As a ___, I want ___, so that ___.

**Acceptance criteria**
- [ ] Given ___ when ___ then ___
- [ ] 

**Design reference**
Link to HLD/LLD section or ADR:

**Test approach**
Unit / Integration / E2E:

**Estimate:** 
**Epic:** 
```

### 10.2 Bug issue `.github/ISSUE_TEMPLATE/bug.md`
```markdown
---
name: Bug
labels: type:bug
---
**What happened**

**Expected**

**Steps to reproduce**

**Logs / correlation id**

**Severity:** blocker / major / minor
```

### 10.3 Pull request `.github/pull_request_template.md`
```markdown
## What
Closes #<issue>

## How
- 

## Tests
- [ ] Unit
- [ ] Integration
- [ ] Manual check

## Checklist
- [ ] DoD satisfied
- [ ] Swagger/docs updated
- [ ] No secrets or PII in logs
- [ ] Migration is backward compatible
```

### 10.4 Daily log `docs/sprints/S1-daily-log.md`
```markdown
### 2026-01-12
- Yesterday:
- Today:
- Blockers:
- Notes / decisions:
```

### 10.5 Sprint planning note `docs/sprints/S1-plan.md`
```markdown
# Sprint 1: Identity & Groups
**Goal:** A user can register, log in, create a group and add members, from the UI.
**Capacity:** 13 pts
| Story | Pts |
|---|---|
| SL-101 ... | 3 |
**Risks:** 
**Definition of success for the demo:** 
```

### 10.6 Retrospective `docs/sprints/S1-retro.md`
```markdown
# Retro: Sprint 1
**Velocity:** planned __ / done __

**Went well**
- 

**Did not go well**
- 

**Surprises**
- 

**Actions (max 3, each with an owner = me and a due date)**
1. 
```

### 10.7 ADR `docs/adr/NNN-title.md`
```markdown
# ADR-NNN: Title
**Status:** Proposed | Accepted | Superseded by ADR-XXX
**Date:** 

## Context
What problem or force requires a decision?

## Decision
What we decided.

## Alternatives considered
- Option A: pros / cons
- Option B: pros / cons

## Consequences
Positive, negative, follow-up work.
```

### 10.8 Risk register `docs/risks.md`

| ID | Risk | Likelihood | Impact | Mitigation | Status |
|---|---|---|---|---|---|
| R1 | Scope creep | High | High | MVP cut line, backlog discipline | Open |
| R2 | Razorpay webhook testing locally | Medium | Medium | Tunnel or simulation script | Open |
| R3 | VM memory limits | Medium | Medium | Heap caps, larger instance | Open |
| R4 | Time gaps / burnout | Medium | High | Small stories, weekly cadence | Open |

---

## 11. Working agreements (for yourself)

1. One story at a time; finish before starting another.
2. Tests are part of the story, not a follow-up.
3. Commit small, merge small, deploy often.
4. If a task takes 2x its estimate, stop, split the story, and note it in the retro.
5. Write the ADR when you make a decision you might forget the reason for.
6. Take one full day off per week.
7. Never write a resume number you have not measured.

---

## 12. How to present this in an interview

- Show the board and the velocity table: "I ran 1-week sprints and re-planned from measured velocity."
- Show one retro with a concrete action and its outcome.
- Show 2 or 3 ADRs: "I chose a modular monolith over microservices because..."
- Show the DoD: "Nothing was Done without tests, a green pipeline, and docs."
