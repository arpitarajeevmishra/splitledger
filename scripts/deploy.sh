#!/usr/bin/env bash
# Deploy the published images to the app host. Run this from your laptop, not the server.
#
#   ./scripts/deploy.sh ubuntu@203.0.113.10          # deploy HEAD
#   ./scripts/deploy.sh ubuntu@203.0.113.10 <sha>    # deploy a specific commit
#
# A deploy is: ship the two files the host needs, pull the images CI already built for
# that commit, restart, and prove the result answers. Nothing is built here -- this
# project has no Docker locally (ADR-012), so the image that gets deployed is the exact
# one the pipeline tested rather than a lookalike rebuilt on a developer machine.
#
# Rolling back is the same command with an older SHA. That is the whole mechanism, and it
# works because images are tagged by commit and the host holds no state (ADR-015).
set -euo pipefail

APP_DIR="/opt/splitledger"
TARGET="${1:-}"
TAG="${2:-$(git rev-parse HEAD)}"

say()  { printf '%s\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m    %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; }
step() { printf '\n\033[1m%s\033[0m\n' "$*"; }
warn() { printf '  \033[33mnote\033[0m  %s\n' "$*"; }

if [[ -z "$TARGET" ]]; then
  say "usage: $0 <user>@<host> [commit-sha]"
  say
  say "Run scripts/provision-oracle.sh on the host first, and make sure"
  say "$APP_DIR/.env exists there (template: deploy/.env.example)."
  exit 2
fi

step "Checking the commit being deployed"
# CI publishes images tagged with the commit SHA, and only from main. Deploying a SHA
# that never reached main means `docker compose pull` fails with a manifest error several
# minutes from now; saying it here costs nothing.
if git merge-base --is-ancestor "$TAG" origin/main 2>/dev/null; then
  ok "$TAG is on origin/main"
else
  warn "$TAG is not on origin/main -- no image was published for it unless you pushed it"
  warn "run 'git fetch origin main' if origin/main is stale locally"
fi
ok "deploying tag $TAG"

step "Shipping the stack files to $TARGET:$APP_DIR"
# Only these two. Everything else the host needs is in its .env, which stays on the host
# and is never overwritten from here -- a deploy must not be able to clobber the secrets.
scp -q deploy/docker-compose.prod.yml deploy/Caddyfile "$TARGET:$APP_DIR/"
ok "docker-compose.prod.yml, Caddyfile"

step "Deploying"
# -T because there is no TTY to allocate and compose is quieter without one.
ssh -T "$TARGET" "TAG='$TAG' APP_DIR='$APP_DIR' bash -euo pipefail -s" <<'REMOTE'
cd "$APP_DIR"

if [[ ! -f .env ]]; then
  echo "  no $APP_DIR/.env on this host. Copy deploy/.env.example there, fill it in," >&2
  echo "  and chmod 600 before deploying." >&2
  exit 1
fi
# A world-readable .env on a public-IP box holds the database password and the JWT
# signing key. Fail rather than quietly deploy with it exposed.
perms=$(stat -c '%a' .env)
if [[ "$perms" != "600" ]]; then
  echo "  $APP_DIR/.env is mode $perms; expected 600. Run: chmod 600 $APP_DIR/.env" >&2
  exit 1
fi

export TAG
# --wait holds until every service reports healthy, so a failed boot fails the deploy
# instead of returning success and leaving a crash-looping container behind.
docker compose -f docker-compose.prod.yml pull --quiet
docker compose -f docker-compose.prod.yml up -d --wait --remove-orphans

# Record what is now deployed. Compose reads TAG from this file, so without this line a
# later `docker compose up -d api` -- which is how the kill switches are meant to be
# flipped (SL-906) -- would quietly pull whatever :latest had become and un-pin the host
# from a known commit. Written only after --wait succeeded, so a failed deploy leaves the
# previous working SHA in place, which is the one a restart should bring back.
if grep -q '^TAG=' .env; then
  sed -i "s|^TAG=.*|TAG=${TAG}|" .env
else
  printf 'TAG=%s\n' "$TAG" >> .env
fi
# sed -i writes through a temporary file, so restate the mode rather than trusting it to
# have been carried across.
chmod 600 .env

docker compose -f docker-compose.prod.yml ps

# The previous commit's images are what a rollback pulls from cache, so keep them and
# only drop what is genuinely unreferenced. `prune -a` here would make rolling back a
# download instead of a restart.
docker image prune -f >/dev/null
REMOTE
ok "containers up and healthy"

step "Smoke test"
# The same endpoint the Dockerfile healthcheck uses, but from outside -- which is what
# also proves Caddy, TLS and the VCN rules, none of which the container can see.
domain=$(ssh -T "$TARGET" "grep -E '^DOMAIN=' $APP_DIR/.env | cut -d= -f2-" | tr -d '\r')
if [[ -z "$domain" ]]; then
  bad "no DOMAIN in $APP_DIR/.env; skipping the external check"
  exit 1
fi
url="https://$domain/api/v1/health-check"
for attempt in 1 2 3 4 5; do
  if curl -fsS --max-time 10 "$url" >/dev/null 2>&1; then
    ok "$url answered"
    say
    say "Deployed $TAG to https://$domain"
    exit 0
  fi
  # First boot includes an ACME round trip, so a couple of failures here are expected.
  say "  waiting for $url (attempt $attempt/5)"
  sleep 6
done

bad "$url did not answer"
say
say "Containers are healthy, so the app is running and something in front of it is not:"
say "  - DNS: does $domain resolve to this instance's public IP?"
say "  - VCN: are there ingress rules for TCP 80 and 443 on the subnet?"
say "  - ACME: ssh in and run"
say "      docker compose -f $APP_DIR/docker-compose.prod.yml logs caddy"
exit 1
