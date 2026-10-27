FROM node:24-bookworm-slim AS build
WORKDIR /app
COPY ai-service/package.json ai-service/package-lock.json ./
RUN npm ci
COPY ai-service/tsconfig.json ./
COPY ai-service/src ./src
RUN npm run build && npm prune --omit=dev

FROM node:24-bookworm-slim
ENV NODE_ENV=production
WORKDIR /app
COPY --from=build --chown=node:node /app/package.json /app/package-lock.json ./
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --chown=node:node ai-service/sql ./sql
USER node
EXPOSE 3001
CMD ["node", "dist/main.js"]

