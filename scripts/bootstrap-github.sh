#!/usr/bin/env bash
# SL-008 — create the GitHub repository and seed the board in one pass.
#
#   ./scripts/bootstrap-github.sh public
#   ./scripts/bootstrap-github.sh private
#
# Idempotent: safe to re-run. Existing labels are updated, existing milestones and
# issues are skipped rather than duplicated.
set -euo pipefail

VIS="${1:-}"
REPO_NAME="splitledger"
DESC="Ledger-first expense splitting and settlement. Razorpay test mode only — no real money moves."

case "$VIS" in
  public|private) ;;
  *) echo "usage: $0 public|private"; exit 2 ;;
esac

# gh is installed by Homebrew, which is not on a non-interactive shell's PATH.
command -v gh >/dev/null 2>&1 || export PATH="/opt/homebrew/bin:$PATH"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
ok()   { printf '  ok    %s\n' "$*"; }
skip() { printf '  --    %s\n' "$*"; }

step "Preflight"
command -v gh >/dev/null || { echo "gh not found"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "Not authenticated. Run: gh auth login"; exit 1; }
ok "gh authenticated as $(gh api user --jq .login)"
[[ -z "$(git status --porcelain)" ]] || { echo "Working tree is dirty; commit first."; exit 1; }
ok "working tree clean, $(git rev-list --count HEAD) commits"

step "Repository"
if git remote get-url origin >/dev/null 2>&1; then
  skip "remote 'origin' already set to $(git remote get-url origin)"
else
  gh repo create "$REPO_NAME" "--$VIS" --source=. --remote=origin --description "$DESC"
  ok "created $VIS repository"
fi
git push -u origin main
ok "pushed main"

SLUG="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
ok "repo: $SLUG"

step "Labels"
# Epics and work types from AGILE.md §4.
while IFS='|' read -r name colour desc; do
  [[ -z "$name" ]] && continue
  gh label create "$name" --color "$colour" --description "$desc" --force >/dev/null
  ok "$name"
done <<'LABELS'
epic:platform|5319E7|Repo, CI, delivery plumbing
epic:identity|1D76DB|Auth, tokens, profile
epic:groups|0E8A16|Groups, membership, invites
epic:expenses|FBCA04|Expenses and split strategies
epic:ledger|D93F0B|Postings, reversals, reconciliation
epic:settlements|C2E0C6|Settlement lifecycle
epic:payments|BFD4F2|Gateway adapters and webhooks
epic:events|D4C5F9|Outbox, bus, consumers
epic:performance|F9D0C4|Caching, load tests, metrics
epic:frontend|C5DEF5|React application
epic:deploy|0052CC|Hosting, CD, operations
type:story|0075CA|User-visible unit of work
type:bug|D73A4A|Something behaves incorrectly
type:spike|FEF2C0|Time-boxed investigation
type:chore|EDEDED|Maintenance, no user-visible change
priority:must|B60205|MVP cut line
priority:should|FBCA04|Strongly recommended
priority:could|C2E0C6|Stretch backlog
LABELS

step "Milestones (S0–S9)"
EXISTING="$(gh api "repos/$SLUG/milestones?state=all" --jq '.[].title' 2>/dev/null || true)"
while IFS='|' read -r title desc; do
  [[ -z "$title" ]] && continue
  if grep -Fxq "$title" <<<"$EXISTING"; then
    skip "$title"
  else
    gh api --method POST "repos/$SLUG/milestones" -f title="$title" -f description="$desc" >/dev/null
    ok "$title"
  fi
done <<'MILESTONES'
S0 Foundations & decisions|Decisions closed, docs corrected, dev stack, green CI
S1 Identity|Register, login, refresh rotation, profile
S2 Groups, membership & invites|Groups with real membership and consent
S3 Money core|Splits and ledger that cannot drift
S4 Balances, corrections & activity|Correct balances, reversible history
S5 Settlements (no gateway)|Settle up, idempotently
S6 Deploy it, door shut|Live on the internet, registration off
S7 Razorpay test mode|Gateway integration against a real webhook URL
S8 Events, cache & performance|Production-grade backend, measured
S9 Open it to the public|Safe and legible to strangers
MILESTONES

step "Seed issues for the next sprint (S1)"
# Only the next sprint is seeded. AGILE.md §3 refines the following sprint mid-sprint,
# so seeding all ten upfront would create sixty issues that are not Ready.
OPEN="$(gh issue list --state all --limit 200 --json title --jq '.[].title' 2>/dev/null || true)"
while IFS='|' read -r title labels body; do
  [[ -z "$title" ]] && continue
  if grep -Fq "$title" <<<"$OPEN"; then
    skip "$title"
  else
    gh issue create --title "$title" --label "$labels" \
      --milestone "S1 Identity" --body "$body" >/dev/null
    ok "$title"
  fi
done <<'ISSUES'
SL-101 Register and login|type:story,epic:identity,priority:must|BCrypt strength 10 or higher; duplicate email returns 409; validation errors as problem+json; password at least 8 characters. Design: docs/LLD.md §12. Estimate: 3.
SL-102 JWT access token plus refresh rotation, and logout|type:story,epic:identity,priority:must|Access token 15 minutes, held in memory. Refresh token 7 days in an httpOnly SameSite=Strict cookie scoped to the refresh path, rotated on every use; reusing an old token revokes the whole chain. Design: docs/adr/011-token-storage.md. Estimate: 3.
SL-103 Rate limits on the auth endpoints|type:story,epic:identity,priority:must|Login 5/min per IP plus email, register 5/hour per IP. Sixth attempt returns 429 with Retry-After. Cache unavailable must not fail the request. Estimate: 2.
SL-104 Frontend auth pages, protected routes and silent refresh|type:story,epic:frontend,priority:must|Access token never in localStorage. A 401 triggers exactly one refresh and one retry, no loops. Logout clears state. Design: docs/LLD.md §14.3. Estimate: 3.
SL-105 GET and PATCH /users/me|type:story,epic:identity,priority:must|Name and UPI id. A user's VPA is never returned to anyone but themselves and a settlement counterparty. Estimate: 2.
ISSUES

step "Project board"
if gh project create --owner "@me" --title "SplitLedger" >/dev/null 2>&1; then
  ok "created project 'SplitLedger'"
else
  skip "could not create the project — 'gh auth login' does not grant the 'project' scope by default."
  echo "        Run: gh auth refresh -s project,read:project    then re-run this script."
fi

step "Done"
echo "  $SLUG"
echo "  Next: set branch protection on main (requires a PR and green CI) in repo settings,"
echo "  then close SL-008 and start S1."
