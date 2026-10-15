# Accounts, Identity and Registry Access

**Date:** 2026-10-15 · **Relates to:** `SPRINT_PLAN.md` SL-001, SL-802, SL-803 · `CICD_DOCKER_CLOUD.md` §3, §7

Short answers: **GitHub — yes, and right now there is nothing to conflict with.** **Docker Hub —
you almost certainly don't need an account, and you should not log in on this machine.** The
Docker Hub credential already stored here is never used by this project, because every container
operation happens on a CI runner or the deploy host, not locally (§4.2).

---

## 1. What is actually on this machine today

Checked before advising, so none of this is guesswork:

| | State |
|---|---|
| Global git `user.name` / `user.email` | **Not set at all** |
| Git credential helpers, `includeIf` rules | None |
| SSH keys | **None** (no `~/.ssh/config`, no keypairs) |
| `gh` CLI | **Not installed** |
| Docker logins | **Docker Hub is logged in**, plus a work AWS ECR registry in `ap-south-1`, via the Docker Desktop credential store |

Two conclusions:

- **For git and GitHub there is no second account to work around yet.** Nothing is configured, so
  the personal account can simply be the default. The directory-scoped setup below still matters —
  not for today, but because retrofitting it *after* commits carry the wrong email is annoying, and
  work git access will probably appear on this machine eventually.
- **For Docker there is an existing work setup, and it is the thing to protect.** See §4.

---

## 2. The distinction that trips people up

These are two separate things, and fixing one does not fix the other:

| | What it controls | Set by |
|---|---|---|
| **Identity** | The name and email stamped on each commit, and therefore which GitHub profile the commit is attributed to | `git config user.name` / `user.email` |
| **Authentication** | Which account is allowed to *push* | SSH key, or an HTTPS credential / token |

You can commit as your personal identity and still push with a work credential, or the reverse.
Both need setting.

---

## 3. GitHub: recommended setup

### 3.1 Identity, scoped to the personal directory

Put this in `~/.gitconfig`:

```gitconfig
[includeIf "gitdir:~/Documents/personal/"]
    path = ~/.gitconfig-personal
```

and this in `~/.gitconfig-personal`:

```gitconfig
[user]
    name = Divyesh Kharade
    email = divyeshkharade@gmail.com
```

The trailing slash on the `gitdir:` pattern matters — it makes the rule apply to everything beneath
that directory. Every repo under `~/Documents/personal/` now gets the personal identity
automatically, and anything added later for work is unaffected.

**Set this before the first commit** (it is now an acceptance criterion on SL-001). Rewriting
authorship afterwards means a rebase over your whole history.

If you would rather not have your address in a public commit log, GitHub issues a
`<id>+<username>@users.noreply.github.com` address — use that as the email and enable *Keep my
email addresses private* in GitHub settings.

### 3.2 Authentication: a dedicated SSH key with a host alias

There are no keys on this machine, so start clean:

```bash
ssh-keygen -t ed25519 -C "splitledger-personal" -f ~/.ssh/id_ed25519_personal
```

Add the public key to the personal GitHub account (Settings → SSH and GPG keys), then create
`~/.ssh/config`:

```
Host github-personal
  HostName github.com
  User git
  IdentityFile ~/.ssh/id_ed25519_personal
  IdentitiesOnly yes
```

Use the alias in the remote, not `github.com`:

```bash
git remote add origin git@github-personal:<your-username>/splitledger.git
ssh -T git@github-personal          # should greet you by username
```

`IdentitiesOnly yes` stops SSH from offering other keys, which is the usual cause of "pushed as the
wrong account". Because the account is selected by the **remote URL**, it cannot leak into a work
repository — that is why this is better than the alternatives:

| Approach | Verdict |
|---|---|
| **Dedicated SSH key + host alias** | **Recommended.** Per-remote, explicit, no global state |
| HTTPS + macOS keychain | One credential per host, so two GitHub accounts overwrite each other |
| `gh auth login` / `gh auth switch` | Works, but the active account is **machine-global** — the wrong footgun on a shared work machine |

### 3.3 `gh` CLI

Not installed, and SL-008 (project board, labels, milestones) wants it:

```bash
brew install gh
gh auth login          # choose SSH, and the key above
```

Remember its account selection is global. For git operations the SSH alias governs; `gh` matters
for issues, the board and setting repository secrets.

### 3.4 Commit signing

Nothing is configured, so nothing to override. If work later sets `commit.gpgsign` globally, add
`[commit] gpgsign = false` (or a personal signing key) to `~/.gitconfig-personal` so personal
commits don't get signed with a work key.

---

## 4. Docker: you probably don't need a Docker Hub account

### 4.1 Pushing images needs GHCR, not Docker Hub

The pipeline already pushes to **GitHub Container Registry**, authenticated with the automatic
`GITHUB_TOKEN`:

```
ghcr.io/<owner>/splitledger-backend:<sha>
ghcr.io/<owner>/splitledger-web:<sha>
```

No Docker Hub account, no extra secret, no extra login. The images land under your personal GitHub
account because that is where the repository lives. For a portfolio project, **make those packages
public** — then the deploy host needs no registry credential either, and anyone can inspect them.

### 4.2 Where Docker actually runs — and whether the existing login is used

"Will Docker use the Docker Hub account that is already logged in?" depends entirely on **which
machine** is running Docker, and there are three, none of which share configuration:

| Where | Uses this machine's Docker Hub login? | Notes |
|---|---|---|
| **This machine**, if you ran `docker build` / `docker pull` | **Yes** — that is exactly what the stored credential is for | But per ADR-012 you never build or run containers locally, so this does not arise |
| **GitHub Actions runners** (all image builds, Testcontainers, the e2e stack) | **No, never** | A runner is a fresh VM with an empty `~/.docker/config.json`. It has no knowledge of your laptop and **pulls anonymously** unless the workflow explicitly logs in |
| **The deploy host** | **No** | Its own machine, its own Docker config |

So the work Docker Hub login is simply **irrelevant** to this project. Every container operation
happens on a machine that has never seen it.

That cuts both ways, and it is the point worth internalising: **because the CI runner is anonymous,
it is subject to anonymous per-IP pull limits, and the existing login does nothing to help.** That
is the whole reason for the optional `DOCKERHUB_*` secrets in §4.4 — they give *the runner* a
credential it would otherwise lack.

One more reason not to wire a work account into this project even where you could: an
authenticated pull consumes **that account's** quota. A personal project should not spend the
employer's.

### 4.3 Do not run `docker login` on this machine

Docker keeps **one credential per registry**. This machine is already logged in to Docker Hub with
a work account (and to a work ECR registry). Logging in with a personal Docker Hub account would
**replace** the Docker Hub credential and sign the work account out of it.

There is no reason to accept that: per ADR-012 you do not build or run containers locally at all.
Image builds happen on CI runners. **Leave the local Docker configuration untouched.**

**If you ever do want a personal Docker login here**, isolate it rather than overwriting the work
one. The CLI reads its configuration directory from `DOCKER_CONFIG`:

```bash
mkdir -p ~/.docker-personal && echo '{}' > ~/.docker-personal/config.json
DOCKER_CONFIG=~/.docker-personal docker login     # work credential untouched
DOCKER_CONFIG=~/.docker-personal docker pull ...
```

The `echo '{}'` matters. This machine uses `credsStore: desktop`, which pushes credentials into the
macOS keychain keyed **by registry URL** — so two accounts for `index.docker.io` would collide in
the keychain even from separate config directories. An alternate config with no `credsStore` keeps
the credential inside that file instead. The trade-off is that it is stored base64-encoded rather
than encrypted, which is acceptable for a read-only token and not for anything else.

Worth knowing, but not worth doing: you do not need a local Docker login for this project.

### 4.4 The one real reason to have a Docker Hub account

Base-image **pull rate limits**. Every base image in the project comes from Docker Hub —
`maven`, `eclipse-temurin`, `node`, `nginx`, `postgres`, `redis`, `caddy`, `redpanda` — and
anonymous pulls are rate-limited per IP. GitHub-hosted runners share IP ranges with everyone else
on GitHub, so anonymous pulls are a genuine source of intermittent, confusing CI failures that look
like network flakes.

The fix is a **read-only** personal access token used only by CI:

1. Docker Hub → Account Settings → Personal access tokens → new token, **Public Repo Read-only**.
2. Add `DOCKERHUB_USERNAME` and `DOCKERHUB_TOKEN` as GitHub Actions secrets.
3. CI logs in before building (see `CICD_DOCKER_CLOUD.md` §3).

A read-only token cannot push or delete anything, so the blast radius if it leaks is a raised pull
quota. Do this when you first see a `toomanyrequests` failure, not before — it is a two-minute fix
and may never be needed.

---

## 5. Where each credential lives

| Credential | Lives in | Notes |
|---|---|---|
| Personal SSH key | `~/.ssh/id_ed25519_personal` + GitHub account | Never leaves the machine |
| `GITHUB_TOKEN` (GHCR push) | Provided automatically by Actions | Nothing to create |
| `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` | GitHub Actions secrets | Optional, read-only, §4.4. Set on the **runner**, which cannot see this machine's login |
| Deploy host SSH key | GitHub Actions secrets (`EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`) | A deploy-only key, not your personal one |
| GHCR read token for the host | Not needed if packages are public | Otherwise a PAT with `read:packages` only |
| App secrets (DB, JWT, Razorpay) | `.env` on the host, `chmod 600` | **Never** in the workflow or the repo |

Nothing in this table belongs in git. The `.gitignore` from SL-001 must cover `.env` before the
first commit.

---

## 6. One thing worth checking

This is a personal portfolio project on what looks like an employer-managed machine. Device
management and employment agreements sometimes have something to say about ownership of work
produced on company hardware. Worth a glance at yours — and if it is ambiguous, a personal machine
removes the question entirely. Nothing about the plan depends on which machine you use.
