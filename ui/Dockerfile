# The web UI as a container: a static site served by nginx.
# Build from the ui/ folder:  docker build -t ledger-ui ui
#
# Stage 1 compiles the app (Node is needed only here). Stage 2 keeps just the compiled files and a small web server,
# so the final image has no Node, no source code and no build tools.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# These addresses are baked into the JavaScript at build time (a browser cannot read server settings). The defaults match
# Docker Compose and kind; pass --build-arg to build for another place.
ARG VITE_KEYCLOAK_AUTHORITY=http://localhost:8180/realms/ledger
ARG VITE_KEYCLOAK_CLIENT_ID=ledger-ui
ARG VITE_LEDGER_API=http://localhost:8080
ARG VITE_TRADING_API=http://localhost:8082
ARG VITE_NOTIFICATION_API=http://localhost:8084
ENV VITE_KEYCLOAK_AUTHORITY=$VITE_KEYCLOAK_AUTHORITY \
    VITE_KEYCLOAK_CLIENT_ID=$VITE_KEYCLOAK_CLIENT_ID \
    VITE_LEDGER_API=$VITE_LEDGER_API \
    VITE_TRADING_API=$VITE_TRADING_API \
    VITE_NOTIFICATION_API=$VITE_NOTIFICATION_API
RUN npm run build

# "unprivileged" nginx runs as a normal user and listens on 8080 (ports below 1024 need root).
FROM nginxinc/nginx-unprivileged:1.31-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=6 CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
