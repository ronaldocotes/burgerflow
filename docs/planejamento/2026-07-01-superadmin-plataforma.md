# Plano: Painel Super-Admin /plataforma do MenuFlow

Data: 2026-07-01 | Autor: Construtor (lab de agentes) | Status: PROPOSTO (aguarda aprovacao do dono)

> Grounding feito no repo real `/home/ronaldo/menuflow` (WSL) em 2026-07-01. Todos os fatos citam
> arquivo:linha. Documento em ASCII (sem acentos) de proposito: edicao via UNC corrompe UTF-8.

---

## 0. Correcoes de premissa (o codigo real vs o pedido)

O pedido citava endpoints/entidades que NAO existem. Verificado no repo:

| Premissa do pedido | Realidade verificada |
|---|---|
| `POST /admin/tenants` existe | NAO existe. Tenant so nasce pelo `DevDataSeeder.kt:91-106` (dev) + auto-create do banco no 1o acesso (`DynamicTenantRoutingDataSource.kt:100`) |
| `POST /admin/tenants/{slug}/migrate` | NAO existe. Existe `GET /admin/tenants/migration-status` (`TenantMigrationAdminController.kt:25-26`) |
| Entidade `Company` no controle | Chama-se `Tenant` (`model/control/Tenant.kt`, tabela `tenants`): slug unique, displayName, subscriptionPlan BASIC/PRO/ENTERPRISE (campo inerte, nada consulta), isActive, expiresAt. SEM databaseUrl (nome derivado `tenant_<slug>`) |
| `CashSession` no controle | E entidade de TENANT (`model/CashSession.kt`) |
| Entitlement/modulos por tenant | NAO existe (grep entitlement/feature/module vazio) |
| Audit log de plataforma | NAO existe. `audit_log` (V17) e por-tenant; controle nao tem trilha |
| Health WAHA/LiteLLM | NAO existe. So iFood tem (`IfoodHealthController.kt:22-28` + circuit breakers R4j) |
| Endpoint `/me` | NAO existe. Front decodifica JWT com `atob` cru (`app/admin/usuarios/page.tsx:86-87`) |

Migrations: CONTROLE em `db/control/migration/` V1..V9 (proxima V10). TENANT em `db/tenant/migration/` V1..V38.

## 1. Problema e usuarios

- Persona unica: o OPERADOR DA PLATAFORMA (o dono, papel SUPER_ADMIN ja existente em `User.kt:89`).
- Dor: gerenciar o SaaS exige terminal/curl/SQL na mao (criar tenant e literalmente INSERT + seeder).
- Plataforma: web only (painel interno; sem app Android — justificado: operador usa desktop).
- Financeiro: tangencia (custo de IA por tenant); sem movimentacao de dinheiro — ledger nao se aplica aqui.

## 2. Estado da arte (pesquisa 2026-07-01, com fontes)

- Entitlements: separar do plano — plano da defaults, tabela de override por tenant decide; checar na API E na UI; para SMB, booleans + limites numericos cobrem 90%, sem vendor (Stigg/LaunchDarkly desnecessarios). Fontes: garrettdimon.com (Data Modeling SaaS Entitlements), AWS SaaS Lens OPS_4, launchdarkly.com/blog/how-to-manage-entitlements-with-feature-flags.
- Billing IA: LiteLLM proxy JA tem spend tracking nativo (`LiteLLM_SpendLogs`, `/spend/logs`, `/global/spend/report`, header `x-litellm-response-cost`) — docs.litellm.ai/docs/proxy/cost_tracking. Custo e SNAPSHOT na gravacao, nunca recalculado na leitura (preco de modelo muda; recalcular reescreve fatura passada) — metronome.com/blog/usage-based-billing.
- Segredos na UI: padrao Stripe/GitHub = mostrar UMA vez na criacao, depois so prefixo/last4 + fingerprint; write-only (novo valor substitui, nunca ha endpoint de reveal) — docs.stripe.com/keys-best-practices.
- Acao destrutiva: padrao GitHub Danger Zone — digitar o nome exato (slug) para habilitar o botao — docs.github.com.
- Impersonation ("logar como tenant"): consenso 2025/26 = perigoso por design; se um dia fizer: sessao NOVA com TTL 15-60min + motivo + audit imutavel + opt-in do tenant (padrao Cal.com) — engineering.pigment.com/2026/04/08/safe-user-impersonation, cal.com/blog account-level-settings. Recomendacao: ADIAR (fora deste plano).
- Salvaguardas super-admin: 2FA obrigatorio, IP allowlist/VPN, audit de TODA acao, sessao curta — docs.directadmin.com hardening, help.salesforce.com session settings.
- Health interno: Resilience4j + Actuator expoe estado CLOSED/OPEN/HALF_OPEN por instancia (`registerHealthIndicator` + `/actuator/circuitbreakers`) — baeldung.com/spring-boot-resilience4j. iFood do MenuFlow ja agrega isso em servico proprio (`IfoodHealthService.kt:46+`).
- Build vs buy (Retool/Forest/Appsmith): com logica proprietaria (secrets AES, modulos, provisioning multi-banco) o custo oculto do low-code supera — build. Fonte: yaro-labs.com/blog/retool-alternatives.

## 3. Visao da solucao (3 linhas)

Novo modulo backend `com.menuflow.platform` com todas as rotas `/admin/**` (controle only, gate duplo
SUPER_ADMIN), duas tabelas novas no controle (`tenant_module` e `platform_audit_log`) e um app
`/plataforma` no Next reusando o molde visual do painel do tenant. Tudo que o operador faz vira auditoria.

## 4. Arquitetura (monolito modular)

### Backend — modulo `com.menuflow.platform`

Pacote novo `backend/src/main/kotlin/com/menuflow/platform/` (nao misturar com `controller/` de tenant):

- `PlatformTenantController` — `/admin/tenants` (GET lista, POST cria, PATCH ativa/desativa, GET {slug}/usage, POST {slug}/migrate)
- `PlatformModuleController` — `/admin/tenants/{slug}/modules` (GET, PUT toggle)
- `PlatformIntegrationsController` — `/admin/integrations/health` (agrega iFood + OpenDelivery + WAHA + LiteLLM)
- `PlatformAiUsageController` — `/admin/ai-usage` (por tenant/mes + custo estimado)
- `PlatformUserController` — `/admin/platform-users` (lista/convida/revoga SUPER_ADMIN)
- `PlatformIfoodAppController` — `/admin/ifood/apps` (CRUD IfoodAppConfig, write-only secrets) + vinculos (`IfoodTenantConfig`)
- `PlatformOpenDeliveryController` — `/admin/tenants/{slug}/open-delivery` (CRUD OpenDeliveryTenantConfig)
- `TenantProvisioningService` — orquestra criacao de tenant (ver 4.3)
- `ModuleGateService` + anotacao `@RequiresModule("X")` — enforcement de entitlement (ver 4.2)
- `PlatformAuditService` — grava `platform_audit_log` em toda mutacao

Mover (ou delegar) os 2 controllers admin existentes para o modulo, sem quebrar rota:
`TenantMigrationAdminController` e `IfoodHealthController` ja respondem sob `/admin/**` com o mesmo gate.

Fatos que sustentam o desenho (verificados):
- Repositorios de CONTROLE nao passam pelo routing datasource — persistence unit propria `@Primary`
  fixa em `menuflow_control` (`ControlDataSourceConfig.kt:24-42`). Rotas `/admin` funcionam mesmo com o
  TenantContext apontando para o tenant "casa" do SUPER_ADMIN.
- Para agir DENTRO de um tenant (ex.: contar pedidos do mes, rodar migration), usar bind manual:
  `TenantContext.set(slug)` + clear em finally — padrao ja usado por jobs/webhooks. Criar helper
  `withTenant(slug) { ... }` que ANTES valida o slug contra a tabela `tenants` (existe + isActive).
  Nunca derivar datasource de input sem essa validacao.

### 4.1 Gate SUPER_ADMIN (defesa em profundidade)

Hoje o gate e SO method-level (`@PreAuthorize("hasRole('SUPER_ADMIN')")`,
`TenantMigrationAdminController.kt:26`); a `SecurityConfig.kt:79-89` nao tem regra de path para
`/admin/**`. Um controller novo esquecido sem anotacao ficaria apenas `authenticated()`. Correcao:

1. Path-level: `requestMatchers("/admin/**").hasRole("SUPER_ADMIN")` na SecurityConfig (cinta).
2. Method-level: manter `@PreAuthorize` em todo controller do modulo (suspensorio).
3. Teste de contrato no CI: request a `/admin/**` com token ADMIN comum => 403; sem token => 401.

IDOR cross-tenant: rotas `/admin` recebem `slug` no path por design (o super-admin age sobre qualquer
tenant). A defesa NAO e escopo do token, e sim: (a) papel SUPER_ADMIN verificado nas 2 camadas;
(b) slug sempre resolvido via `tenants` do controle (404 se nao existe) antes de qualquer bind;
(c) validacao estrita do slug `^[a-z0-9-]{3,30}$` — o slug vira identificador de banco no
`CREATE DATABASE "tenant_<slug>"` (`DynamicTenantRoutingDataSource.kt:100`): injecao aqui e RCE de SQL.
Regra: rejeitar no DTO (Bean Validation) E no service (fail-closed).

### 4.2 Entitlement de modulos — `tenant_module` no CONTROLE

Decisao (SUBSTITUI a D2 da Fase 3, que sugeria `enabled_modules` no tenant_config): o entitlement mora
no banco de CONTROLE. Motivos: (1) o /plataforma nao pode abrir datasource de tenant para ligar modulo;
(2) jobs de polling (iFood/OpenDelivery) rodam FORA de request de tenant e precisam saber quem esta
habilitado consultando 1 banco, nao N; (3) e o modelo validado no Posthumus (entitlements + RBAC).

Modelo: `plan defaults (codigo) + override por tenant (tabela)`:

```sql
-- control V10__tenant_module.sql
CREATE TABLE tenant_module (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
  module_key VARCHAR(40) NOT NULL,          -- enum no codigo (abaixo)
  enabled BOOLEAN NOT NULL,
  limits_json JSONB,                        -- ex.: {"ai_monthly_token_cap": 2000000}
  updated_by_user_id UUID NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (tenant_id, module_key)
);
```

`ModuleKey` (enum Kotlin): `IFOOD`, `OPEN_DELIVERY`, `AI_COPILOT`, `WHATSAPP_BOT`, `DELIVERY`,
`GROWTH`, `LOYALTY`. Sem linha na tabela => vale o default do plano (BASIC = core; modulos pagos OFF).
Licao Posthumus aplicada: NAO gatear API core compartilhada (auth, cardapio, PDV, KDS, caixa) — modulo
so gateia o que e opcional.

Enforcement (3 pontos, todos fail-closed p/ modulos pagos):
- HTTP: `@RequiresModule` via interceptor/aspect nos controllers dos modulos opcionais => 403 com
  mensagem clara ("Modulo X nao habilitado para esta empresa").
- Jobs: `IfoodPollingJob`/`OpenDeliveryPollingJob` filtram por `enabled` antes de polar.
- UI tenant: sidebar esconde item de modulo desabilitado (so estetica; autorizacao real e no backend).
Cache: Caffeine local TTL 60s por `(tenantId, moduleKey)` — staleness de 60s e aceitavel para toggle.

### 4.3 Provisionamento de tenant (hoje inexistente via API)

`POST /admin/tenants` — passos idempotentes, em ordem, com registro do progresso:
1. Valida slug (regex acima) + unicidade (`tenants.slug` unique ja garante).
2. INSERT `tenants` (isActive=true, plano escolhido).
3. Toca o datasource do slug => auto-create do banco fisico + Flyway V1..V38 (mecanismo JA existente:
   `DynamicTenantRoutingDataSource.kt:65,90-104`). Registrar no ledger de migracao do controle.
4. Cria o admin do tenant por CONVITE (reusar `UserInvitation` V4: tokenHash SHA-256 + expiracao),
   devolvendo o link — melhor que senha temporaria (nunca transita segredo em claro).
5. `platform_audit_log` da criacao.
Retry seguro: cada passo checa se ja foi feito (slug ja existe => 409; banco ja criado => segue; convite
pendente => reemite). Sem saga distribuida — e 1 monolito + N bancos, basta idempotencia por passo.

Ativar/desativar: `PATCH /admin/tenants/{slug}` (isActive). ENFORCEMENT NOVO NECESSARIO — verificado
que `isActive` hoje nao bloqueia nada em runtime: (a) checar no login (`AuthService`); (b) checar no
`JwtAuthFilter` via cache 60s do status do tenant (JWT e stateless; sem isso, sessao viva sobrevive ate
expirar). Desativar tenant NAO derruba o banco — so bloqueia acesso (dado e sagrado; exclusao fisica
fica FORA deste plano, danger zone futura com backup + espera).

### 4.4 Saude das integracoes

`GET /admin/integrations/health` (cache server-side 30-60s, nao marretar terceiros):
- iFood: reusar `IfoodHealthService` (ja agrega `ifood_tenant_config` + estado dos circuit breakers).
- OpenDelivery: servico novo espelhando o padrao iFood — le `open_delivery_tenant_config`
  (status, lastSuccessfulPoll, consecutiveFailures por plataforma NINETY_NINE/RAPPI) + CB do R4j.
- WAHA: ping `GET {wahaBaseUrl}/api/sessions` timeout 2s (health novo; hoje nao existe).
- LiteLLM: `GET /health/liveliness` (ou `/v1/models`) timeout 2s (health novo).
Semaforo por integracao: OK / DEGRADED / DOWN + ultimo erro + timestamp. Falha de health NUNCA derruba
o painel (try/catch por card — padrao fail-open de leitura).

### 4.5 Billing / uso de IA

- Hoje: `ai_usage` (V5) so tem tokens + requests, upsert mensal por tenant (`AiUsage.kt`), gravado
  best-effort por `AiUsageService.record()` (`AiUsageService.kt:23-52`).
- V11 (controle): adicionar `estimated_cost_usd_micros BIGINT NOT NULL DEFAULT 0` acumulado no
  `record()` usando tabela de precos por modelo em config — SNAPSHOT na gravacao (estado da arte;
  recalcular na leitura reescreve historico quando o preco muda). Micros de USD = inteiro, sem float.
- Tela: consumo por tenant/mes (tokens in/out, requests, custo estimado) + total da plataforma.
  Grafico simples (Recharts, ja padrao nosso). Rotular sempre "custo ESTIMADO".
- NAO reimplementar metering do LiteLLM: se um dia precisar de granularidade por request, ligar o
  spend tracking nativo do proxy (`/spend/logs`) — hoje o agregado mensal do MenuFlow basta.

### 4.6 Chaves iFood e Open Delivery (segredos write-only)

- Reusar a criptografia JA existente (AES-256-GCM + IV por campo + keyVersion —
  `IfoodAppConfig.kt`, `OpenDeliveryTenantConfig.kt`).
- Contrato de API: segredo e WRITE-ONLY. POST/PUT aceitam `clientSecret`/`appToken` em claro no body
  (TLS) e cifram; GET devolve APENAS `last4` + fingerprint SHA-256 curto + keyVersion + datas
  (created/lastUsed/tokenExpiresAt). NAO existe endpoint de reveal. Rotacao = enviar valor novo
  (substitui, incrementa keyVersion).
- UI: campo tipo password com placeholder `****abc4 (v2)`; botao "Substituir chave"; nunca render do
  valor. Log NUNCA contem o segredo (mascarar no toString das entidades ja e pratica — conferir).

### 4.7 Frontend — `app/plataforma/`

Novo route-group com layout PROPRIO (nao reusa o ClientLayout do tenant):
- Guard client-side: decode do JWT (mesmo padrao `atob` de `app/admin/usuarios/page.tsx:86-87`);
  role != SUPER_ADMIN => redirect /login. E so UX — autorizacao real e o backend (2 camadas, 4.1).
- Molde visual: o MESMO do painel tenant (sidebar fixa w-64 + topbar, tokens `--color-primary-*` de
  `globals.css`, tema CLARO apenas — dark mode do MenuFlow esta quebrado, N/A justificado no DoD).
  Diferenciar com faixa/badge "PLATAFORMA" no topo (operador precisa saber onde esta).
- Paginas: `/plataforma` (visao geral: contadores + semaforo saude), `/plataforma/tenants` (lista +
  criar), `/plataforma/tenants/[slug]` (detalhe: uso, modulos com toggles, migration status + botao
  migrar, danger zone desativar digitando o slug), `/plataforma/integracoes`, `/plataforma/ia`,
  `/plataforma/usuarios`, `/plataforma/ifood-apps`.
- Estados obrigatorios em toda lista/fetch: loading skeleton / vazio com acao / erro com retry /
  sucesso (toast). Toggle de modulo = otimista com rollback visivel em erro.

## 5. Modelo de dados (novas migrations — CONTROLE only)                    [Craudio/Curador]

- `V10__tenant_module.sql` — tabela acima + indice `(tenant_id)`.
- `V11__ai_usage_cost.sql` — `ALTER TABLE ai_usage ADD COLUMN estimated_cost_usd_micros BIGINT NOT NULL DEFAULT 0;`
- `V12__platform_audit_log.sql`:

```sql
CREATE TABLE platform_audit_log (
  id BIGSERIAL PRIMARY KEY,
  actor_user_id UUID NOT NULL,
  actor_email VARCHAR(255) NOT NULL,
  action VARCHAR(60) NOT NULL,           -- TENANT_CREATE, MODULE_TOGGLE, KEY_ROTATE, ...
  target_tenant_id UUID,
  target_entity VARCHAR(60),
  before_json JSONB,
  after_json JSONB,                      -- NUNCA segredo/PII em claro (mascarar antes)
  source_ip VARCHAR(45),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_platform_audit_tenant ON platform_audit_log(target_tenant_id, created_at DESC);
```

- (F2) `V13__tenant_usage_snapshot.sql` — snapshot diario por job: `tenant_id, snapshot_date,
  orders_month INT, db_size_bytes BIGINT, last_login_at TIMESTAMPTZ` (unique tenant+date). Motivo:
  "uso" exige entrar em N bancos (`withTenant` + count de orders + `pg_database_size`) — caro para
  fazer sincrono na listagem; job noturno + botao "atualizar agora" por tenant resolve.
- NENHUMA migration de tenant neste plano. Regra de ouro: aplicar V10..V13 no controle da A1 ANTES do
  deploy do codigo (banco proprio na A1 — mesma licao do Posthumus).

## 6. Seguranca e permissoes                                                [Centuriao]

- RBAC: `/admin/**` = SUPER_ADMIN em path-level + method-level (4.1). Deny-by-default preservado
  (`anyRequest().authenticated()` + `@PreAuthorize`). Teste 401/403 no CI.
- Slug = input critico (vira nome de banco): regex estrita + resolucao via tabela antes de bind (4.1c).
- Segredos: write-only/last4 (4.6); nunca em log/audit (mascarar before/after do audit).
- Auditoria: TODA mutacao do /plataforma grava `platform_audit_log` (quem, o que, antes->depois, IP).
  Append-only, sem UPDATE/DELETE na app.
- Sessao: token no localStorage e o padrao atual do app (risco XSS conhecido). Mitigacao nesta fase:
  TTL de access token ja curto + CSP (verificar se ha) + nenhum dado sensivel novo no front. Migrar
  para cookie httpOnly e projeto a parte (nao inflar este).
- Exposicao de rede (recomendacao ao Capataz): allowlist no Caddy para `/api/v1/admin/*` e
  `/plataforma` (IP fixo ou Tailscale) — barato e corta a superficie inteira. Decisao D5.
- 2FA TOTP para SUPER_ADMIN: F3 (nao bloqueia F1; registrado como pendencia de hardening).
- Impersonation: FORA do plano (decisao consciente — ver estado da arte; se voltar, sessao nova +
  TTL + opt-in do tenant + audit).
- Centuriao revisa formalmente ANTES do merge da F1 (gate de plataforma inteira e alvo de valor alto).

## 7. UX                                                                     [Nick]

- Persona: operador tecnico (o dono). Densidade de informacao > estetica; tabelas com busca simples.
- Danger zone (desativar tenant): secao vermelha ao fim do detalhe, botao habilita so apos digitar o
  slug exato (padrao GitHub).
- Toggle de modulo: switch com estado pendente (spinner inline) + toast de sucesso/erro + rollback
  visual se falhar. Trava dupla-submissao.
- Saude: cards com semaforo (verde/ambar/vermelho) + "ha Xmin" + ultimo erro expandivel. Cor nunca e
  o unico sinal (icone + texto).
- Estados: skeleton/vazio/erro/sucesso em TODAS as paginas (PADROES 6). Sem mascaras de CPF/moeda aqui
  (nao ha esses campos), EXCETO custo IA em USD formatado a partir de micros (inteiro).
- A11y: foco visivel, labels, contraste >=4.5:1 com os tokens existentes.

## 8. Financeiro

Nao ha movimentacao de dinheiro neste modulo. Custo IA = estimativa em micros de USD (inteiro,
snapshot na gravacao). Se um dia virar COBRANCA ao tenant (add-on IA), ai sim entra ledger/idempotencia
— fora de escopo, registrado na decisao D2.

## 9. Fases incrementais

### F1 — Fundacao: tenants + modulos + auditoria + gate (a mais urgente)
Backend: modulo `platform/`, path-rule SecurityConfig, V10+V12, `PlatformTenantController`
(lista/cria/ativa/desativa + enforcement isActive no login/filtro), provisioning (4.3),
`PlatformModuleController` + `ModuleGateService` + `@RequiresModule` aplicado a iFood/OpenDelivery/
AI/Bot/Delivery, `PlatformAuditService` em toda mutacao. Front: layout /plataforma + visao geral
(so contadores) + tenants (lista/criar/detalhe com modulos + danger zone) + migration status/migrar.
DoD F1:
- [ ] `/admin/**` com ADMIN comum => 403; sem token => 401 (teste no CI)
- [ ] Criar tenant e2e: linha em `tenants` + banco `tenant_x` + Flyway V38 + convite admin gerado
- [ ] Retry de criacao nao duplica nada (idempotencia por passo, teste)
- [ ] Toggle de modulo reflete em <=60s no gate HTTP e nos jobs de polling (teste)
- [ ] Tenant desativado: login bloqueado + sessao viva barrada em <=60s
- [ ] Toda mutacao aparece no `platform_audit_log` com before/after mascarado
- [ ] V10+V12 aplicadas no controle da A1 ANTES do deploy
- [ ] Estados de tela 4/4 + danger zone digitando slug + tema claro ok (dark N/A justificado)
- [ ] Revisao formal do Centuriao concluida

### F2 — Operacao: saude das integracoes + configs iFood/OpenDelivery + uso por tenant
Backend: `PlatformIntegrationsController` (4.4, com health WAHA/LiteLLM novos + servico OpenDelivery
espelhando iFood), `PlatformIfoodAppController` + `PlatformOpenDeliveryController` (4.6, write-only),
V13 + job noturno de usage snapshot + botao refresh. Front: /plataforma/integracoes + /ifood-apps +
aba open-delivery no detalhe do tenant + colunas de uso na lista de tenants.
DoD F2:
- [ ] GET de config NUNCA devolve segredo (teste de contrato: response nao contem o valor postado)
- [ ] Health com terceiro FORA nao derruba a pagina (card DOWN, resto vivo)
- [ ] Cache de health 30-60s verificado (nao marreta WAHA/LiteLLM)
- [ ] Rotacao de chave: valor novo cifrado, keyVersion incrementada, audit gravado
- [ ] Usage snapshot roda a noite e o refresh manual funciona; listagem nao abre N bancos sincrono

### F3 — Billing IA + usuarios da plataforma + hardening
Backend: V11 + custo no `AiUsageService.record()` (snapshot) + `PlatformAiUsageController`;
`PlatformUserController` (convite SUPER_ADMIN via UserInvitation, revogar com protecao "nao revogar a
si mesmo"/"ultimo super-admin"); 2FA TOTP para SUPER_ADMIN. Front: /plataforma/ia (tabela + grafico
Recharts) + /plataforma/usuarios. Capataz: allowlist Caddy (D5).
DoD F3:
- [ ] Custo acumula em micros (inteiro) na gravacao; mudanca de preco NAO altera meses passados (teste)
- [ ] Revogar o ultimo SUPER_ADMIN e impossivel (teste)
- [ ] 2FA obrigatorio no login de SUPER_ADMIN
- [ ] Tela IA rotula "estimado" e bate com ai_usage (conferencia manual 1 tenant)

## 10. Conselho de agentes (quem cobriu o que)

Sintetizados por leitura direta dos conhecimentos (sem subagentes de persona); pesquisa de estado da
arte e grounding do repo delegados a 2 subagentes (relatorios integrados acima).
- Craudio (backend): persistence unit de controle vs routing (4.0/4.3), idempotencia do provisioning,
  BOPLA/allowlist de DTO, expand->contract. IMPLEMENTA o modulo platform.
- Curador (banco): V10..V13 idempotentes no controle da A1 antes do deploy; snapshot de uso em vez de
  query N-bancos sincrona; dado e sagrado (desativar != dropar). IMPLEMENTA as migrations.
- Centuriao (seguranca): gate duplo, slug->CREATE DATABASE como injecao critica, segredos write-only,
  audit append-only sem PII, exposicao de rede. REVISA antes do merge F1.
- Nick (UI): molde sidebar/topbar existente, estados 4/4, danger zone, semaforo nao-so-cor.
  IMPLEMENTA o front /plataforma.
- Capataz (infra): allowlist Caddy/Tailscale para /admin (D5); health endpoints baratos.
- Portinari (dataviz): grafico de uso IA simples e honesto (F3).
- Testador (QA): testes de contrato 401/403, idempotencia de provisioning, write-only de segredo.

## 11. Riscos e decisoes pendentes

Riscos:
- R1 Slug malicioso vira `CREATE DATABASE` (RCE SQL) — mitigado por regex+resolucao via tabela (4.1c);
  teste adversarial obrigatorio.
- R2 JWT de SUPER_ADMIN em localStorage: XSS no front = takeover da plataforma. Mitigacao parcial
  (TTL/CSP); D5 (allowlist de rede) reduz muito; cookie httpOnly fica como projeto futuro.
- R3 Provisioning parcial (banco criado, convite falhou): idempotencia por passo + audit mostra onde
  parou; sem saga complexa.
- R4 Cache de entitlement 60s: janela em que modulo desligado ainda responde. Aceito (documentado).
- R5 Health de WAHA depende de como o WAHA esta exposto na A1 (suponho `GET /api/sessions` acessivel
  da rede Docker — NAO confirmei; validar com Capataz antes de F2).
- R6 `isActive` sem enforcement hoje: se F1 atrasar o item do filtro, desativar tenant e cosmetico.
  Por isso esta no DoD F1, nao como melhoria.

Decisoes pendentes (com recomendacao):
- D1 Lista final de ModuleKey + defaults por plano BASIC/PRO/ENTERPRISE (recomendo: BASIC=core only;
  PRO=+IFOOD+GROWTH+LOYALTY; ENTERPRISE=tudo; override individual sempre possivel).
- D2 Cobrar IA do tenant como add-on? (recomendo: adiar; por ora so visibilidade de custo).
- D3 Impersonation: recomendo NAO fazer (ver 2/6).
- D4 2FA ja na F1 ou F3? (recomendo F3 — F1 ja e grande; risco coberto por D5 enquanto isso).
- D5 Allowlist Caddy/Tailscale para `/api/v1/admin/*` + `/plataforma` (recomendo SIM, junto da F1 —
  1h de Capataz).
- D6 Qual tenant "casa" do usuario SUPER_ADMIN (hoje o seeder so cria no demo)? Recomendo criar tenant
  reservado `plataforma` (isActive, sem cardapio) para nao poluir o demo.

## 12. Definition of Done geral (PADROES.md aplicado)

- Mascaras: N/A (sem CPF/tel/CEP aqui); moeda IA em micros formatada — unico campo.
- Tema: tokens existentes, claro only (dark quebrado no MenuFlow = N/A justificado).
- Validacao client+server com mensagem por campo (slug, displayName, plano).
- Estados 4/4 em toda tela; RBAC deny-by-default nas 2 camadas; re-autorizacao do objeto = slug via
  tabela de controle; auditoria em toda mutacao; query parametrizada (JPA); segredo fora de
  front/git/log; fail-closed nos gates; idempotencia no provisioning e nos toggles.

## 10. Registro de execucao (adicionado 2026-07-08 — via git, main @2e474c4)

- 2026-07-01: **F1 — Fundacao** entregue: `a68271e` (backend tenants/modulos/auditoria/gate) + `77d3d3b` (frontend layout + guard SUPER_ADMIN) + `8588d93` (@RequiresModule + ModuleGateAspect, cache 60s) + `ab76e1d` (fix Centuriao: SLUG_REGEX sem hifen + @Email no adminEmail).
- 2026-07-02: **F2 — Operacao** e **F3 — Billing/usuarios/2FA** entregues: `d6141f6` (health das integracoes, uso/custo de IA por tenant, usuarios SUPER_ADMIN com anti-lockout) + `41fdf83` (**2FA TOTP persistente AES-256-GCM, V15** — secret sobrevive a restart).
- ⚠️ Pendencia de deploy (Capataz): `MENUFLOW_TOTP_AES_KEY` **obrigatoria** no `.env.prod` da A1 (sem ela o 2FA quebra no boot); migrations de CONTROLE aplicadas antes do deploy.
- Status geral: **F1–F3 concluidas e em `main`.**
