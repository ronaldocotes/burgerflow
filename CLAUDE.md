# CLAUDE.md — Memória canônica do MenuFlow (repo `burgerflow`)

> Memória operacional para o Claude Code. Consolida `README.md`,
> `docs/alinhamento-tecnico.md` e o estado real do código vivo. Quando este
> arquivo divergir do código/testes, **o código vence** — e então atualize aqui.

## 1. O que é

**MenuFlow** — SaaS de gestão para hamburguerias SMB: **KDS + PDV + Delivery
nativo + IA**, com _growth_ embutido. Espinha do produto (blueprint ClickEscale):

```
cardápio → link/QR → pedido → payment_paid → financeiro → cliente → recompra
```

## 2. Stack (fundação MVP — Kafka e K3s ADIADOS)

| Camada | Tecnologia | Estado |
|---|---|---|
| Backend | Spring Boot 3.4 / Kotlin (JDK 21) | maduro, testado |
| Frontend web | Next.js 16 / React 19 / Tailwind | em construção |
| Mobile | React Native 0.86 / React 19 | em construção |
| IA | FastAPI (Python 3.11) | parcial: só `health` + `demand_forecasting` |
| Dados | PostgreSQL 16, Redis 7 | — |
| Infra | Docker Compose + Caddy (Oracle A1) | produção MVP |

## 3. Arquitetura essencial

- **Multi-tenant = DB-por-tenant.** Banco de **controle** (`menuflow_control`)
  guarda `tenants`, `users` (auth) e o _ledger_ de migração. Cada tenant tem seu
  próprio banco físico `tenant_<slug>`, provisionado + migrado (Flyway) **no 1º
  acesso** (`MF_DB_AUTOCREATE=true`), roteado por `TenantContext`.
- **Dinheiro sempre em centavos** (`Long`), nunca float.
- **Auth:** JWT + refresh tokens, RBAC via Spring Security. Login é escopado por
  `(tenantId, email)`. Papéis: `ADMIN, MANAGER, STAFF, CASHIER, KITCHEN,
  DELIVERY, OPERATOR, DRIVER, WAITER, SUPER_ADMIN`.
- **Backend context-path:** `/api/v1`. Rotas liberadas sem JWT:
  `/auth/**`, `/public/**`, `/actuator/health`, `/webhooks/**`, swagger.
  `/admin/**` exige `SUPER_ADMIN`.
- **Realtime:** WebSocket/STOMP em `/api/v1/ws` (auth no frame CONNECT).
- **Pagamentos (contrato canônico):** `Order → PaymentIntent → ProviderAdapter →
  WebhookEvent idempotente → Payment confirmed → Outbox → KDS/Growth/relatórios`.
  Todo meio (PIX, cartão, Asaas, Mercado Pago…) passa por esse contrato comum.
- **IA nunca inventa** preço/produto/estoque/prazo/taxa/margem/ROAS — só consulta
  ferramentas do domínio; copiloto com aprovação humana. `KAFKA_ENABLED=false`.

## 4. Layout do repo

```
backend/   Spring Boot/Kotlin — com.menuflow.{controller,service,repository,model,
           dto,tenant,security,seeder,platform,delivery,dispatch,ifood,event,...}
frontend/  Next.js (app router): app/, components/, lib/, types/
mobile/    React Native
ia/        FastAPI (app/, main.py)
docker/    compose de dev + DEPLOY-A1.md + Caddyfile
docs/      alinhamento-tecnico.md, clickescale-blueprint, growth-center,
           auditorias/, planejamento/, outputs/
scripts/   deploy-prod-a1.sh, seed-demo-prod.sh, seed-demo-official.py, ...
compose.prod.yml   env.prod.template   (raiz)
```

## 5. Produção (Oracle A1)

- **Compose canônico:** `compose.prod.yml` (raiz). **Sem** Kafka/Zookeeper/pgAdmin.
- **Env:** `.env.prod` na raiz (gitignored), criado de `env.prod.template`.
- **Borda TLS:** Caddy compartilhado do host via rede Docker externa `web`.
- **Domínio:** `https://menuflow.duckdns.org`. Deploy: `scripts/deploy-prod-a1.sh`
  (roda no host A1; PULL, sem SSH público — só Tailscale).
- **Frontend embute build-args** `NEXT_PUBLIC_*` — inclusive
  `NEXT_PUBLIC_TENANT_SLUG=demo` e `NEXT_PUBLIC_API_URL=https://menuflow.duckdns.org/api/v1`.

## 6. Cardápio público (`/cardapio`) — armadilhas conhecidas

- A vitrine pública busca `GET /api/v1/public/{demo}/menu`. O endpoint é
  `permitAll`; retorna **404 se o tenant não existe**.
- **O tenant `demo` NÃO é criado em produção automaticamente.** O `DevDataSeeder`
  é `@Profile("dev")`. Em prod, use **`scripts/seed-demo-prod.sh`** (idempotente:
  cria tenant+admin no controle via `docker exec` no Postgres + roda
  `seed-demo-official.py`). Requer `MF_DEMO_ADMIN_PASSWORD` no `.env.prod`; o
  `deploy-prod-a1.sh` o chama automaticamente.
- **Páginas públicas NÃO devem ler o token de auth.** `/cardapio` lia
  `menuflow_access_token` do localStorage e caía num caminho autenticado → um
  token de admin expirado dava **401** ("Erro 401"). Vitrine de cliente sempre
  usa o endpoint público, sem `Authorization`. (Corrigido; `/acompanhar` e
  `/motoboy` já faziam certo.)

## 7. Comandos

```bash
# Backend
cd backend && ./gradlew bootRun --args='--spring.profiles.active=dev'
./gradlew test
# Frontend (CI: lint com --max-warnings=0, type-check tsc --noEmit)
cd frontend && npm ci && npm run dev
npm run lint && npm run type-check
# IA
cd ia && pip install -r requirements.txt && pytest tests/
# Deploy prod (no host A1)
scripts/deploy-prod-a1.sh
```

## 8. Convenções

- **Conventional Commits:** `feat: fix: docs: refactor: perf: test: build: ci: chore:`.
- Branch de trabalho do Claude: `claude/<assunto>`; PRs abertos como **draft**.
- **Nunca** commitar `.env.prod`/segredos. Migração Flyway: nunca editar arquivo
  já aplicado — criar `V{n+1}__...`.
- CI (`.github/workflows/ci.yml`): jobs Backend/Frontend/Mobile/IA + SonarQube +
  Docker build. Frontend quebra com qualquer warning de lint.

## 9. Fonte de verdade (ordem de confiança)

1. Código vivo + testes.
2. `compose.prod.yml`, `env.prod.template`, `scripts/deploy-prod-a1.sh`, `docker/DEPLOY-A1.md`.
3. `docs/alinhamento-tecnico.md`.
4. `docs/growth-center-trafego-pago.md` e `docs/clickescale-blueprint-restaurante.md`.
5. `README.md` (quando não contradizer o acima).

## 10. Agentes do laboratório

Papéis de referência (a memória detalhada de cada um — `conhecimento.md`,
`aprendizado.md`, `heuristicas.md` — vive fora do repo e **não está versionada**):

| Agente | Domínio |
|---|---|
| **Construtor** | Arquitetura, planejamento, orquestração |
| **Craudio** | Backend Spring Boot/Kotlin, modelagem, performance |
| **Gepeto** | IA/FastAPI, integrações, WhatsApp |
| **Curador** | Banco de dados, multi-tenant, otimização de queries |
| **Nick** | UI/UX, frontend Next.js / React Native |
| **Centurião** | Segurança, OWASP, threat model, RBAC |
| **Testador** | Testes, QA, CI/CD |

> Para versionar a memória dos agentes: mover os `.md` para `docs/agentes/` e
> referenciá-los aqui.
