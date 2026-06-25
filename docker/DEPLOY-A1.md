# MenuFlow — deploy na Oracle A1 (backend-first)

> Artefatos de produção (perfil `prod` + compose + Caddy). **Quem executa o deploy
> na A1 é o Capataz** — a A1 é ponto único compartilhado (SISATER/Alicia/Posthumus
> já rodam atrás de um Caddy em 80/443). Nada aqui sobe sozinho.

## O que já está pronto (Craudio)
- `backend/src/main/resources/application-prod.yml` — perfil `prod`: **JWT secret
  obrigatório** (sem fallback de dev → app não sobe sem `MF_JWT_SECRET`), rate-limit
  no Redis, actuator só `health` (`show-details: never`).
- `backend/Dockerfile` — já roda `-Dspring.profiles.active=prod` (multi-stage, jar,
  usuário não-root, healthcheck).
- `docker/docker-compose.prod.yml` — caddy + postgres + redis + backend. **Sem
  Kafka/Zookeeper/pgadmin/kafka-ui** (o backend não tem `@KafkaListener` → sobe sem
  broker; economiza RAM). Só o Caddy expõe 80/443; resto na rede interna.
- `docker/Caddyfile` — TLS automático; faz upgrade de **WebSocket `/ws`** (KDS/
  Delivery), proxy `/api/v1/*`, bloqueia `/api/v1/actuator/*`.
- `docker/env.prod.example` — template de segredos (gerar na A1 com `openssl`;
  nome sem ponto inicial pois `.env.*` é gitignorado).

## Passos na A1 (Capataz, via Tailscale `ssh ubuntu@100.95.28.100`)
1. `git clone`/`pull` do repo (deploy key) em `/home/ubuntu/menuflow` (ou no
   padrão de diretório usado pelos outros projetos).
2. `cd docker && cp env.prod.example .env.prod` e preencher com `openssl rand`:
   - `MF_JWT_SECRET` = `openssl rand -base64 48`
   - `MF_DB_PASSWORD` / `SPRING_REDIS_PASSWORD` = `openssl rand -base64 24`
   - `SITE_ADDRESS` = `menuflow.163-176-212-54.sslip.io` (ou domínio real).
3. **Coordenar a borda 80/443 com o Caddy já existente** — opções:
   - merge deste site no Caddyfile global existente (recomendado), OU
   - rodar o Caddy deste compose numa porta alternativa atrás do Caddy global.
4. `docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build`.
5. **Migrations:** o Flyway do banco de **controle** roda no boot do app; cada
   **tenant** migra no 1º acesso. Para migrar bancos já existentes fora de banda:
   ```
   ../scripts/apply-migrations.sh \
     "jdbc:postgresql://localhost:5432/menuflow_control?user=U&password=P" \
     "jdbc:postgresql://localhost:5432/tenant_<slug>?user=U&password=P"
   ```
6. Validar: `curl -fsS https://$SITE_ADDRESS/api/v1/actuator/health` → `{"status":"UP"}`.

## Notas de segurança (handoff Centurião/Capataz)
- O `cd.yml` antigo do repo é **aspiracional** (Slack/e-mail, domínios inexistentes,
  `./gradlew flywayMigrate` não configurado) e **não reflete** o padrão real da A1
  (pull-deploy por cron, sslip.io, Tailscale). Não usar como está.
- A1: deploy é **PULL** (cron `~/pull-deploy.sh`), porta 22 pública **fechada**
  (SSH só via Tailscale). Backup off-server é obrigatório (Object Storage).
- Postgres: usar role de **menor privilégio** que ainda possa `CREATE DATABASE`
  (provisão de tenants), não o superuser `postgres`.
