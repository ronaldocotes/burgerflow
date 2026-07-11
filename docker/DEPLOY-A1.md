# MenuFlow — deploy na Oracle A1

> Artefatos de produção (perfil `prod` + compose + Caddy). **Quem executa o deploy
> na A1 é o Capataz** — a A1 é ponto único compartilhado (SISATER/Alicia/Posthumus
> já rodam atrás de um Caddy em 80/443). Nada aqui sobe sozinho.

## Fonte de verdade atual

- Compose canonico: `compose.prod.yml` na raiz do repo.
- Env canonico: `.env.prod` na raiz do repo, criado a partir de `env.prod.template`.
- Dominio atual: `https://menuflow.duckdns.org`.
- Borda TLS: Caddy compartilhado do host, via rede Docker externa `web`.
- Script canonico de deploy no host: `scripts/deploy-prod-a1.sh`.

Os arquivos `docker/docker-compose.prod.yml` e `docker/Caddyfile` ficam como referencia standalone para um ambiente isolado. Eles nao sao o caminho atual do host compartilhado.

## O que já está pronto
- `backend/src/main/resources/application-prod.yml` — perfil `prod`: **JWT secret
  obrigatório** (sem fallback de dev → app não sobe sem `MF_JWT_SECRET`), rate-limit
  no Redis, actuator só `health` (`show-details: never`).
- `backend/Dockerfile` — já roda `-Dspring.profiles.active=prod` (multi-stage, jar,
  usuário não-root, healthcheck).
- `compose.prod.yml` — Postgres + Redis + backend + frontend. **Sem
  Kafka/Zookeeper/pgadmin/kafka-ui**. Backend e frontend entram tambem na rede
  externa `web` para o Caddy compartilhado.
- `env.prod.template` — template de segredos para `.env.prod` na raiz.
- `.github/workflows/smoke.yml` — smoke seguro do dominio atual, sem SSH e sem deploy.

## Passos na A1 (Capataz, via Tailscale)
1. `git clone`/`pull` do repo (deploy key) em `/home/ubuntu/menuflow` (ou no
   padrão de diretório usado pelos outros projetos).
2. `cp env.prod.template .env.prod` e preencher com `openssl rand`:
   - `MF_JWT_SECRET` = `openssl rand -base64 48`
   - `MF_DB_PASSWORD` / `SPRING_REDIS_PASSWORD` = `openssl rand -base64 24`
   - `IFOOD_ENCRYPTION_KEY` = `openssl rand -base64 32` — cifra iFood + segredo TOTP
     do 2FA (reusa o mesmo cifrador, migration V15). Sem prod fallback fail-fast:
     a app sobe com placeholder se faltar, mas qualquer cifra feita com ele fica
     ilegível quando a chave real for definida depois. Definir ANTES do primeiro
     deploy que envolva iFood/2FA e nunca rotacionar com segredos já persistidos.
3. Confirmar que a rede externa do Caddy existe:
   ```bash
   docker network inspect web
   ```
4. Confirmar que o Caddy compartilhado tem rota para:
   - `menuflow-frontend:3000` para `/`
   - `menuflow-backend:8080` para `/api/v1/*`, `/ws` e `/ws-sockjs/*`
   - `/api/v1/actuator/health` liberado para smoke
   - demais `/api/v1/actuator/*` bloqueados
5. Rodar os gates antes do deploy:
   ```bash
   scripts/run-local-gates.sh
   ```
6. Deploy versionado:
   ```bash
   scripts/deploy-prod-a1.sh
   ```
7. **Tenant demo (cardápio público):** o build do frontend é tenant-agnóstico
   — o storefront resolve o tenant pela URL (`/l/{tenant}/{slug}` →
   `/cardapio?tenant=`), e o código cai no fallback `"demo"` quando não há
   query/slug (rota legada `/cardapio`), sem precisar de
   `NEXT_PUBLIC_TENANT_SLUG` no build. O `DevDataSeeder` que cria esse tenant
   só roda no perfil `dev` — em prod ninguém o cria sozinho. O
   `scripts/deploy-prod-a1.sh` chama `scripts/seed-demo-prod.sh`
   (idempotente: tenant + admin no controle via `docker exec` no Postgres +
   `seed-demo-official.py` pela API) desde que `MF_DEMO_ADMIN_PASSWORD` esteja
   no `.env.prod`. Sem essa variável o seed é pulado com aviso e, num banco
   novo, `/cardapio` fica fora do ar (404 em `/api/v1/public/demo/menu`).
8. **Migrations:** o Flyway do banco de **controle** roda no boot do app; cada
   **tenant** migra no 1º acesso. Para migrar bancos já existentes fora de banda
   (recomendado ANTES de subir código que dependa de uma migration nova —
   expand-first): `scripts/apply-migrations.sh` roda o Flyway anexado à MESMA
   rede Docker do container Postgres (auto-detectada a partir de
   `menuflow-postgres`, sem publicar porta no host — `--network host` não
   funciona aqui porque `compose.prod.yml` não expõe a porta do Postgres).
   Use o hostname interno do compose (`postgres:5432`, o mesmo que
   `MF_DB_HOST` usa) nas URLs, nunca `localhost`:
   ```
   scripts/apply-migrations.sh \
     "jdbc:postgresql://postgres:5432/menuflow_control?user=U&password=P" \
     "jdbc:postgresql://postgres:5432/tenant_<slug>?user=U&password=P"
   ```
   `scripts/check-tenant-migrations.sh --apply-command` já gera esse comando
   pronto (URLs com o host interno correto) a partir dos tenants ativos no
   banco de controle — revisar antes de rodar.
9. Validar:
   ```bash
   curl -fsS https://menuflow.duckdns.org/
   curl -fsS https://menuflow.duckdns.org/api/v1/actuator/health
   ```

## Notas de segurança (handoff Centurião/Capataz)
- O `.github/workflows/cd.yml` esta desativado de proposito ate ser refeito sobre
  `compose.prod.yml`, Caddy compartilhado e migracao controlada de tenants.
- A1: deploy é **PULL** (cron `~/pull-deploy.sh`), porta 22 pública **fechada**
  (SSH só via Tailscale). Backup off-server é obrigatório (Object Storage).
- Postgres: usar role de **menor privilégio** que ainda possa `CREATE DATABASE`
  (provisão de tenants), não o superuser `postgres`.
