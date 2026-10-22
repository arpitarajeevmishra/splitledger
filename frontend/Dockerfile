# The built app is static files; nginx serves them. No Node in the runtime image -- a
# production web server has no reason to carry a package manager and a toolchain.

FROM node:24-alpine AS build
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

HEALTHCHECK --interval=15s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://localhost/ >/dev/null || exit 1
