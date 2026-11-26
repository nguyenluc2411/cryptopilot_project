# syntax=docker/dockerfile:1.7
# CryptoPilot AI Service image (T-004). Build context: ai-service/.
#   docker build -t cryptopilot-ai-service ai-service

FROM node:22.23.5-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22.23.5-alpine
# T-095: Alpine's security updates, and no package manager at run time: every Trivy finding in the Node.js
# packages was in the npm bundled with the image (tar, glob, minimatch, pacote), which the service never runs.
RUN apk upgrade --no-cache \
    && rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack /opt/yarn-* \
    && rm -f /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack /usr/local/bin/yarn /usr/local/bin/yarnpkg
ENV NODE_ENV=production HOST=0.0.0.0 PORT=7070
WORKDIR /app
COPY --from=build --chown=node:node /src/node_modules ./node_modules
COPY --from=build --chown=node:node /src/dist ./dist
COPY --chown=node:node package.json ./
USER node
EXPOSE 7070
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
    CMD wget -qO- http://127.0.0.1:7070/health >/dev/null || exit 1
CMD ["node", "dist/server.js"]
