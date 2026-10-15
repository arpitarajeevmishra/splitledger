#!/usr/bin/env bash
# Verify the managed dev data stores are reachable before you start the app (SL-004).
#
# Scope: this checks that required variables are set and that each host:port
# ACCEPTS A TCP CONNECTION. It deliberately does not check credentials — those are
# verified the moment Flyway connects on startup, and doing it here would mean
# shipping database drivers into a shell script.
set -euo pipefail

ENV_FILE="${1:-.env}"
fail=0

say()  { printf '%s\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m    %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; fail=1; }
skip() { printf '  --    %s\n' "$*"; }

if [[ ! -f "$ENV_FILE" ]]; then
  say "No $ENV_FILE found. Copy .env.example to .env and fill it in."
  exit 1
fi

# Load without executing arbitrary code: only KEY=VALUE lines.
while IFS='=' read -r key value; do
  [[ "$key" =~ ^[A-Z_][A-Z0-9_]*$ ]] || continue
  export "$key=${value}"
done < <(grep -E '^[A-Z_][A-Z0-9_]*=' "$ENV_FILE" || true)

# tcp HOST PORT LABEL
tcp() {
  local host="$1" port="$2" label="$3"
  if [[ -z "$host" || -z "$port" ]]; then bad "$label: could not parse host/port"; return; fi
  if command -v nc >/dev/null 2>&1; then
    if nc -z -G 6 "$host" "$port" >/dev/null 2>&1 || nc -z -w 6 "$host" "$port" >/dev/null 2>&1; then
      ok "$label reachable ($host:$port)"
    else
      bad "$label unreachable ($host:$port)"
    fi
  else
    if (exec 3<>"/dev/tcp/$host/$port") >/dev/null 2>&1; then
      ok "$label reachable ($host:$port)"
    else
      bad "$label unreachable ($host:$port)"
    fi
  fi
}

say "Checking $ENV_FILE"
say
say "Required variables"
for v in DB_URL DB_USER DB_PASSWORD JWT_SECRET; do
  if [[ -n "${!v:-}" && "${!v}" != *"<host>"* ]]; then ok "$v is set"; else bad "$v is empty or still a placeholder"; fi
done
if [[ -n "${JWT_SECRET:-}" && ${#JWT_SECRET} -lt 32 ]]; then
  bad "JWT_SECRET is ${#JWT_SECRET} chars; HS256 wants at least 32"
fi

say
say "Connectivity"
# jdbc:postgresql://host:port/db?params  (port optional, defaults to 5432)
db_hostport="${DB_URL#*//}"; db_hostport="${db_hostport%%/*}"
db_host="${db_hostport%%:*}"
db_port="${db_hostport##*:}"; [[ "$db_port" == "$db_host" ]] && db_port=5432
tcp "$db_host" "$db_port" "PostgreSQL"

if [[ -n "${REDIS_URL:-}" ]]; then
  r="${REDIS_URL#*//}"; r="${r##*@}"; r="${r%%/*}"
  tcp "${r%%:*}" "${r##*:}" "Redis"
else
  skip "REDIS_URL empty — in-process Caffeine cache will be used (ADR-015)"
fi

if [[ -n "${SMTP_HOST:-}" ]]; then
  tcp "$SMTP_HOST" "${SMTP_PORT:-587}" "SMTP"
else
  skip "SMTP_HOST empty — mail disabled"
fi

if [[ -n "${KAFKA_BOOTSTRAP:-}" ]]; then
  tcp "${KAFKA_BOOTSTRAP%%:*}" "${KAFKA_BOOTSTRAP##*:}" "Kafka"
else
  skip "KAFKA_BOOTSTRAP empty — in-process LocalEventBus will be used (ADR-015)"
fi

say
if [[ "$fail" -eq 0 ]]; then
  say "All checks passed. Credentials are still unverified until the app starts."
else
  say "Some checks failed. Fix the above before running the backend."
fi
exit "$fail"
