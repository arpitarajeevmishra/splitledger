# The built app is static files; nginx serves them. No Node in the runtime image -- a
# production web server has no reason to carry a package manager and a toolchain.

# Built on the runner's own architecture: `npm run build` emits static files that are the
# same bytes everywhere, so there is nothing for an emulated arm64 build stage to do but
# take longer. The nginx runtime stage below is what varies per architecture.
FROM --platform=$BUILDPLATFORM node:24-alpine AS build
WORKDIR /build

COPY package.json package-lock.json ./
# npm ci, not install: the lockfile is the input, and a build that quietly resolves a
# different tree than CI tested is a build nobody can reason about.
RUN npm ci

COPY . .
RUN npm run build

FROM nginx:1.29-alpine AS runtime

# The SPA talks to /api/v1 on its own origin (finding C13): one image runs in every
# environment, and the refresh cookie's SameSite=Strict only works because the app and the
# API share an origin. Caddy terminates TLS in front and routes /api to the backend.
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

# 127.0.0.1, not localhost: the config above says `listen 80`, which binds IPv4 only,
# while /etc/hosts maps localhost to ::1 as well -- and busybox wget tries ::1 first and
# gets a connection refused. The backend's healthcheck can say localhost because the JVM
# binds dual-stack; nginx here does not, so the check names the address it actually
# serves. Without this the container never reports healthy, and compose's
# `depends_on: service_healthy` then stops Caddy from starting at all.
HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
