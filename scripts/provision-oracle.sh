#!/usr/bin/env bash
# Prepare a fresh Oracle Cloud Always Free ARM instance to run the production stack.
#
# Run this ON the server, once, as a user with sudo. It is idempotent: running it again
# after a reboot or a partial failure is safe and is the intended way to re-check a box.
#
# Scope: this installs Docker and opens the host firewall. It cannot touch the VCN
# security list -- that lives in Oracle's control plane, not on the instance, and the
# script says so at the end rather than leaving you to discover it from a timeout.
#
# The box stays disposable (ADR-015): Postgres is managed and elsewhere, so nothing here
# is worth backing up. Destroying the instance and re-running this script is a supported
# recovery path, not a disaster.
set -euo pipefail

APP_DIR="/opt/splitledger"
RUN_USER="${SUDO_USER:-$(id -un)}"

# iptables-persistent asks debconf whether to save the current rules, and a prompt in a
# script run over ssh hangs forever rather than failing.
export DEBIAN_FRONTEND=noninteractive

say()  { printf '%s\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m    %s\n' "$*"; }
step() { printf '\n\033[1m%s\033[0m\n' "$*"; }
warn() { printf '  \033[33mnote\033[0m  %s\n' "$*"; }

if [[ "$(id -u)" -ne 0 ]]; then
  say "Re-running under sudo."
  exec sudo -E "$0" "$@"
fi

step "Checking the machine"
arch="$(uname -m)"
if [[ "$arch" != "aarch64" ]]; then
  warn "architecture is $arch, not aarch64 -- fine, but the Ampere free tier is arm64"
else
  ok "aarch64 (Ampere)"
fi
mem_mb=$(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1024 ))
# The JVM takes 75% of whatever the container is given (backend/Dockerfile), and nginx
# and Caddy are rounding error next to it. Below ~2GB the API will start but GC will
# dominate under any real load.
if (( mem_mb < 1800 )); then
  warn "${mem_mb}MB of RAM: the API will be GC-bound. 6GB (1 OCPU A1) is the sized shape."
else
  ok "${mem_mb}MB of RAM"
fi

step "Installing Docker Engine and the Compose plugin"
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  ok "already installed ($(docker --version | cut -d, -f1))"
else
  # Docker's own apt repository, not Ubuntu's docker.io: the distro package lags and
  # ships no compose plugin, and `docker compose` is what deploy/docker-compose.prod.yml
  # is written for.
  apt-get update -qq
  apt-get install -y -qq ca-certificates curl gnupg
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu %s stable\n' \
    "$(dpkg --print-architecture)" "$(. /etc/os-release && echo "$VERSION_CODENAME")" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io \
    docker-buildx-plugin docker-compose-plugin
  ok "installed $(docker --version | cut -d, -f1)"
fi
systemctl enable --now docker >/dev/null 2>&1
ok "docker service enabled"

if ! id -nG "$RUN_USER" | tr ' ' '\n' | grep -qx docker; then
  usermod -aG docker "$RUN_USER"
  ok "added $RUN_USER to the docker group (log out and back in for it to apply)"
else
  ok "$RUN_USER is already in the docker group"
fi

step "Opening the host firewall for HTTP and HTTPS"
# Oracle's Ubuntu images ship an iptables INPUT chain that REJECTs everything except SSH.
# This is the single most common reason a correctly deployed stack on OCI is unreachable:
# the VCN rule gets added, the host rule does not, and the connection just hangs. Insert
# at the head of the chain so the ACCEPT lands before that catch-all REJECT.
opened=0
for port in 80 443; do
  for cmd in iptables ip6tables; do
    command -v "$cmd" >/dev/null 2>&1 || continue
    if "$cmd" -C INPUT -p tcp --dport "$port" -m conntrack --ctstate NEW -j ACCEPT >/dev/null 2>&1; then
      ok "$cmd: $port already allowed"
    else
      "$cmd" -I INPUT -p tcp --dport "$port" -m conntrack --ctstate NEW -j ACCEPT
      ok "$cmd: allowed $port"
      opened=1
    fi
  done
done
if (( opened )); then
  # Without this the rules vanish on reboot and the box comes back unreachable.
  apt-get install -y -qq iptables-persistent >/dev/null 2>&1 || true
  netfilter-persistent save >/dev/null 2>&1 && ok "rules persisted across reboot" \
    || warn "could not persist iptables rules -- re-run this script after a reboot"
fi

step "Creating $APP_DIR"
install -d -o "$RUN_USER" -g "$RUN_USER" -m 0755 "$APP_DIR"
ok "$APP_DIR owned by $RUN_USER"

step "Done. Two things remain, and neither can be done from this box."
cat <<'NEXT'
  1. VCN ingress: in the Oracle console, open the instance's subnet security list (or
     NSG) and add stateful ingress rules for TCP 80 and 443 from 0.0.0.0/0. The host
     firewall above is only half of it.

  2. The server .env: copy deploy/.env.example to /opt/splitledger/.env, fill it in, and
     chmod 600. DOMAIN must already resolve to this instance's public IP before the
     first deploy -- Caddy fetches a certificate over ACME on boot, and the HTTP-01
     challenge fails against a name that points somewhere else.

  Then, from your laptop:  ./scripts/deploy.sh <user>@<this-host>
NEXT
