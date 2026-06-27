FROM node:20.19.4-alpine AS builder

# Build-time config para NEXT_PUBLIC_* (embutidas no bundle; nao sao segredos).
# Passadas via build.args no compose.prod.yml.
ARG NEXT_PUBLIC_API_URL=http://localhost:8080/api/v1
ARG NEXT_PUBLIC_WS_URL=ws://localhost:8080
ARG NEXT_PUBLIC_ENVIRONMENT=development
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL
ENV NEXT_PUBLIC_WS_URL=$NEXT_PUBLIC_WS_URL
ENV NEXT_PUBLIC_ENVIRONMENT=$NEXT_PUBLIC_ENVIRONMENT

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN mkdir -p public && npm run build

FROM node:20.19.4-alpine AS runner

WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000

COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
COPY --from=builder /app/public ./public

RUN addgroup -S nextuser && adduser -S nextuser -G nextuser &&     chown -R nextuser:nextuser /app

USER nextuser

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3     CMD wget -q --spider http://localhost:3000/ || exit 1

CMD ["node", "server.js"]
