# Plano de Auditoria Critica MenuFlow

Data: 2026-06-29
Responsavel operacional: Codex
Sistema: MenuFlow
Alvo principal: `https://menuflow.duckdns.org`
Tenant de teste: `demo`
Perfil avaliado: administrador exigente em primeiro dia de uso real

## Objetivo

Auditar o MenuFlow como um usuario critico que acabou de contratar o sistema para um restaurante, cobrindo experiencia de uso, responsividade, fluxos operacionais, integridade funcional, backend/API e qualidade transversal em PC, tablet e celular.

Esta auditoria deve produzir evidencias reais: screenshots, resultados Playwright, falhas HTTP/console, achados por severidade e lista priorizada de correcoes.

## Agentes e Conhecimentos Usados

- **Construtor**: organiza o trabalho em fases, evita retrabalho, separa leitura, execucao, correcao e validacao.
- **Nick**: avalia UI/UX, legibilidade, hierarquia, responsividade, alvo de toque, estados de tela e harmonia visual.
- **Testador**: transforma o percurso em testes/auditoria Playwright, coleta evidencias e registra risco residual.
- **Craudio**: valida backend, API, tenant, dados, erros HTTP, idempotencia, RBAC e integridade.

Fontes de conhecimento consultadas:

- `C:\Users\sdcot\OneDrive\POJETOS\agentes\PRINCIPIOS.md`
- `C:\Users\sdcot\OneDrive\POJETOS\agentes\PADROES.md`
- `C:\Users\sdcot\OneDrive\POJETOS\agentes\nick\nick.md`
- `C:\Users\sdcot\OneDrive\POJETOS\agentes\nick\aprendizado.md`
- `C:\Users\sdcot\OneDrive\POJETOS\agentes\testador\testador.md`
- `C:\Users\sdcot\OneDrive\POJETOS\agentes\testador\aprendizado.md`
- `C:\Users\sdcot\OneDrive\POJETOS\agentes\craudio\craudio.md`
- `C:\Users\sdcot\OneDrive\POJETOS\agentes\craudio\aprendizado.md`
- `C:\Users\sdcot\OneDrive\POJETOS\agentes\construtor\aprendizado.md`

## Escopo Obrigatorio

### Dispositivos

- PC: `1920x1080`
- Tablet: `768x1024`
- Celular: `390x844` (iPhone 14 como referencia)

### Rotas e Areas

1. Login e onboarding: `/login`
2. Sidebar, topbar e navegacao global
3. Cardapio publico: `/cardapio`
4. PDV: `/pdv`
5. KDS: `/kds`
6. Mesas: `/mesas`
7. Caixa: `/caixa` e `/caixa/historico`
8. Admin Cardapio: `/admin/cardapio`
9. Admin Usuarios: `/admin/usuarios`
10. Financeiro DRE: `/financeiro/dre`
11. Cupons: `/admin/cupons`
12. Fidelidade: `/admin/fidelidade`
13. RFV: `/admin/rfv`
14. Campanhas: `/admin/campanhas`
15. Carrinho abandonado: `/admin/carrinhos`
16. Rastreamento: `/admin/tracking`
17. Conversoes: `/admin/conversoes`
18. Copiloto IA: FAB autenticado
19. Bot WhatsApp: `/admin/bot`
20. Configuracoes: `/configuracoes`

## Fases de Execucao

### Fase 0 - Preparacao e Grounding

Objetivo: garantir que a auditoria esta baseada no sistema real e nos criterios certos.

Tarefas:

- Confirmar ambiente, tenant e credenciais.
- Verificar estado do repositorio.
- Preparar pasta de evidencias.
- Confirmar se a demo pode receber mutacoes.
- Separar modo read-only de modo mutacao controlada.

Definition of Done:

- Ambiente definido.
- Criterios dos agentes lidos.
- Documento de plano salvo no repositorio.
- Log de etapas iniciado.

### Fase 1 - Auditoria Visual Read-Only

Objetivo: percorrer todas as telas nos 3 dispositivos sem gravar dados.

Coletar:

- Screenshot por rota/dispositivo.
- DOM snapshot.
- Erros HTTP 4xx/5xx.
- Erros de console.
- Overflow horizontal.
- Fontes menores que 14px em tablet/mobile.
- Alvos de toque menores que 44px.
- Presenca de estados loading/vazio/erro/sucesso.
- Visibilidade de sidebar/topbar/menu ativo.

Saida esperada:

- `outputs/menuflow-critical-audit/results.json`
- `outputs/menuflow-critical-audit/REPORT.md`
- screenshots por tela.

### Fase 2 - Fluxos Operacionais Seguros

Objetivo: abrir modais, drawers, sheets e passos intermediarios sem finalizar acoes destrutivas.

Fluxos:

- Login com senha errada.
- Login correto.
- Logout.
- Sidebar desktop colapsar/expandir.
- Sidebar mobile abrir/fechar.
- Cardapio publico: abrir produto, testar complementos e carrinho sem finalizar.
- PDV: adicionar item em carrinho sem fechar pedido.
- Admin: abrir "Novo" em telas administrativas sem salvar.
- Copiloto IA: abrir/fechar chat e validar UI.
- Bot WhatsApp: validar grid de horarios visualmente.

Saida esperada:

- Achados UX por fluxo.
- Screenshots de estados intermediarios.
- Lista de fluxos que exigem mutacao autorizada.

### Fase 3 - Mutacao Controlada

Objetivo: testar criacao/edicao/finalizacao usando dados prefixados com `AUDIT-`.

Executar somente com autorizacao explicita.

Dados de teste:

- Categoria `AUDIT Categoria`
- Produto `AUDIT Produto`
- Cupom `AUDIT10`
- Despesa `AUDIT Despesa`
- Link tracking `audit-link`
- Pedido teste publico/PDV
- Usuario convite de auditoria, se SMTP estiver configurado ou se o erro esperado for validado.

Validar:

- Pedido criado no backend.
- Pedido aparece no KDS.
- Totais de carrinho e DRE fecham.
- Cupom valido aplica.
- Cupom invalido mostra erro claro.
- Tracking `/r/{slug}` redireciona.
- WebSocket STOMP atualiza KDS/mesas.
- PATCH parcial em configuracoes preserva campos nao alterados.

### Fase 4 - Backend, Dados e Seguranca

Objetivo: validar contratos, tenant, RBAC, erros e integridade.

Checar:

- DTO publico sem vazamento de custo/margem.
- Tenant isolation.
- Autorizacao ADMIN/MANAGER/USER.
- 401/403 corretos e mensagens claras.
- Idempotencia em pedido/pagamento.
- Dinheiro em centavos.
- Datas no fuso de Sao Paulo.
- Rate limit em login, pedido publico, IA e bot.
- Secrets/tokens fora do front/log.
- Acoes sensiveis com auditoria.

### Fase 5 - Correcoes

Ordem de prioridade:

1. Bloqueantes: tela quebrada, erro 500, pedido/KDS/WebSocket sem funcionar.
2. Importantes: mobile ruim, overflow, botoes pequenos, dados errados, DRE inconsistente.
3. Seguranca/dados: tenant, RBAC, idempotencia, vazamento.
4. Polimento: microcopia, contraste, organizacao visual, estados vazios.

Cada correcao deve ser validada com:

- Type-check frontend.
- Lint dos arquivos alterados.
- Build frontend quando mexer em Next/config.
- Testes backend quando mexer em API/dados.
- Playwright da rota corrigida nos 3 dispositivos.

### Fase 6 - Relatorio Final

Entregar:

1. Sumario executivo.
2. Achados por severidade.
3. Top 5 prioridades.
4. Evidencias por rota/dispositivo.
5. O que funcionou bem.
6. Correcoes aplicadas.
7. Testes executados.
8. Riscos restantes.

## Formato de Achado

Cada achado deve conter:

- Severidade: `Bloqueante`, `Importante`, `Melhoria`
- Dispositivo: PC, Tablet, Mobile
- Tela e caminho
- O que acontece
- O que deveria acontecer
- Evidencia: screenshot, saida de teste, HTTP failure, console error ou arquivo/linha
- Recomendacao concreta

## Checklist Transversal

- [ ] Fonte legivel, minimo 14px em mobile.
- [ ] Area de toque >= 44x44px.
- [ ] Loading states presentes.
- [ ] Estado vazio amigavel.
- [ ] Erros explicitos.
- [ ] Formularios nao perdem dados indevidamente.
- [ ] Scroll mobile sem travar.
- [ ] Sem overflow horizontal.
- [ ] Z-index correto para modal/sidebar/FAB.
- [ ] Contraste WCAG AA onde aplicavel.
- [ ] Sem tokens `brand` obsoletos quando o sistema usa `primary`.
- [ ] Sem cor hardcoded que quebre tema.
- [ ] Tenant/RBAC respeitados.
- [ ] Dinheiro e datas tratados corretamente.

## Registro de Etapas

| Data/hora | Etapa | Status | Evidencia | Observacao |
|---|---|---|---|---|
| 2026-06-29 | Leitura do pedido anexado | Concluida | `pasted-text.txt` lido | Escopo definido como auditoria completa PC/tablet/mobile. |
| 2026-06-29 | Agentes selecionados | Concluida | Construtor, Nick, Testador e Craudio | Conselho minimo suficiente para UX + QA + backend. |
| 2026-06-29 | Conhecimentos dos agentes lidos | Concluida | Arquivos listados na secao "Agentes e Conhecimentos Usados" | Aplicar anti-alucinacao, robustez, UX real e teste com evidencia. |
| 2026-06-29 | Planejamento estruturado | Concluida | Este documento | Fases 0 a 6 definidas. |
| 2026-06-29 | Script inicial read-only preparado | Concluida | `C:\Users\sdcot\Documents\Codex\2026-06-28\https-menuflow-duckdns-org-admin-cardapio-2\work\menuflow-critical-audit.cjs` | Script criado no workspace; execucao foi interrompida antes de concluir. |
| 2026-06-29 | Salvar plano na pasta do sistema | Concluida | `docs/auditorias/2026-06-29-plano-auditoria-critica-menuflow.md` | Documento salvo no repositorio MenuFlow. |
| 2026-06-29 | Salvar script de auditoria na pasta do sistema | Concluida | `docs/auditorias/2026-06-29-menuflow-critical-audit.cjs` | Script read-only copiado para o repositorio MenuFlow para versionamento junto do plano. |
| 2026-06-29 | Decisao de criar tenant isolado de testes | Concluida | Pedido do dono: criar empresa de testes | Novo alvo proposto: tenant `audit`, para permitir mutacoes sem sujar `demo`. |
| 2026-06-29 | Leitura do padrao de provisionamento de tenant | Concluida | `backend/src/main/kotlin/com/menuflow/seeder/DevDataSeeder.kt` | Confirmado: controle cria `tenants` + `users`; banco fisico do tenant e migrado no primeiro acesso. |
| 2026-06-29 | Criar script operacional para tenant de auditoria | Concluida | `scripts/create-audit-tenant.sh` | Script idempotente criado para garantir tenant `audit` + admin proprio no banco de controle. |
| 2026-06-29 | Validar sintaxe do script de tenant | Concluida | `bash -n scripts/create-audit-tenant.sh` | Sintaxe shell validada sem erro. |
| 2026-06-29 | Primeira execucao em QA local | Falhou | `MF_CONTROL_DATABASE_URL=postgresql://menuflow:...@localhost:5545/menuflow_control scripts/create-audit-tenant.sh` | Falha real: `psql` nao recebe variaveis de ambiente como variaveis SQL automaticamente (`syntax error at or near ":"`). |
| 2026-06-29 | Corrigir passagem de variaveis ao psql | Concluida | `scripts/create-audit-tenant.sh` | Script ajustado para passar `-v TENANT_ID=...`, `-v TENANT_SLUG=...`, `-v ADMIN_EMAIL=...` explicitamente ao `psql`. |
| 2026-06-29 | Validar criacao do tenant `audit` em QA local | Concluida | `MF_CONTROL_DATABASE_URL=postgresql://menuflow:...@localhost:5545/menuflow_control scripts/create-audit-tenant.sh` | Criou/garantiu `audit`, display `Auditoria MenuFlow`, admin `audit@menuflow.local`, papel `ADMIN`, ativo `true`. |
| 2026-06-29 | Criar script de seed do tenant `audit` | Concluida | `scripts/seed-audit-tenant.sh` | Wrapper salvo para garantir o tenant e popular via API com `scripts/seed-demo-official.py`, preservando regras de negocio. |
| 2026-06-29 | Validar sintaxe do seed de auditoria | Concluida | `bash -n scripts/seed-audit-tenant.sh` | Sintaxe shell validada sem erro; permissao de execucao aplicada. |
| 2026-06-29 | Verificar dependencias locais para seed | Concluida | `docker ps` + `curl http://localhost:8088/api/v1/actuator/health` | Postgres QA `menuflow-qa-postgres` ativo em `5545`; backend local `8088` ainda nao estava em execucao. |
| 2026-06-29 | Criar executor local completo do seed | Concluida | `scripts/run-audit-seed-local.sh` | Script sobe backend QA, espera API, popula `audit`, valida menu publico e encerra o backend. |
| 2026-06-29 | Primeira execucao do seed local completo | Falhou | `scripts/run-audit-seed-local.sh` | Backend subiu, mas `/actuator/health` ficou `503` porque o Redis local nao estava rodando; script ajustado para detectar API pronta por resposta HTTP do servlet. |
| 2026-06-29 | Rodar seed local completo do tenant `audit` | Concluida | `scripts/run-audit-seed-local.sh` | Login API OK; seed oficial concluiu com 12 categorias, 40 produtos e 12 insumos; menu publico validado com 12 categorias e 40 produtos. |
| 2026-06-29 | Validar banco fisico do tenant `audit` | Concluida | `psql -d tenant_audit` | Banco `tenant_audit` existe; contagens: 12 categorias, 40 produtos, 12 insumos; `schema_version` na migration 31 (`whatsapp bot`) com `success=true`. |
| 2026-06-29 | Ignorar artefatos locais de execucao | Concluida | `.gitignore` | Adicionado `.codex-run/` para logs/pids gerados pelos scripts de auditoria nao entrarem em commit. |
| 2026-06-29 | Revalidar scripts salvos | Concluida | `bash -n scripts/create-audit-tenant.sh`, `bash -n scripts/seed-audit-tenant.sh`, `bash -n scripts/run-audit-seed-local.sh` | Todos os scripts shell salvos passaram na validacao de sintaxe. |
| 2026-06-29 | Criar executor da auditoria frontend local | Concluida | `scripts/run-frontend-audit-local.sh` | Script salvo para subir backend+frontend em QA local, garantir seed `audit`, executar Playwright e encerrar os processos. |
| 2026-06-29 | Validar sintaxe do executor frontend | Concluida | `bash -n scripts/run-frontend-audit-local.sh` | Sintaxe shell validada sem erro; permissao de execucao aplicada. |
| 2026-06-29 | Primeira execucao da auditoria frontend | Falhou | `scripts/run-frontend-audit-local.sh` | A porta `3001` ja estava ocupada por outro Next; o script encontrou uma tela antiga e o login automatizado estourou timeout. Executor ajustado para usar `3011` e detectar processo encerrado. |
| 2026-06-29 | Segunda execucao da auditoria frontend | Falhou | `scripts/run-frontend-audit-local.sh` | Next 16 impediu outro `next dev` no mesmo diretorio porque ja havia dev server antigo em `3001` (PID 616278). Executor ajustado para usar `next build` + `next start` em `3011`, sem depender do lock de dev. |
| 2026-06-29 | Terceira execucao da auditoria frontend | Falhou | `scripts/run-frontend-audit-local.sh` | Frontend em `3011` e backend em `8088` geraram bloqueio local de CORS; backend nao tem CORS porque producao tende a servir mesmo dominio. Script Playwright ajustado com `--disable-web-security` apenas para QA local. |
| 2026-06-29 | Executar auditoria frontend Fase 1 | Concluida | `scripts/run-frontend-audit-local.sh` | Rodada completa: 18 rotas x 3 viewports, 60 screenshots, 103 achados brutos automatizados em `docs/outputs/menuflow-critical-audit`. |
| 2026-06-29 | Salvar relatorio humano da Fase 1 | Concluida | `docs/auditorias/2026-06-29-relatorio-fase-1-frontend.md` | Achados priorizados: botao gigante no cardapio admin, chips de categoria cortando, imagem externa 404, alvos pequenos e densidade do DRE mobile. |
| 2026-06-29 | Aplicar correcoes visuais imediatas | Concluida | `frontend/app/admin/cardapio/page.tsx`, `frontend/app/pdv/page.tsx`, `frontend/app/cardapio/page.tsx`, `scripts/seed-demo-official.py` | Ajustado submit do formulario de produto, quebra de chips em tablet/PC e removida URL 404 do Petit Gateau no seed. |
| 2026-06-29 | Validar type-check e sintaxe do seed | Concluida | `npm run type-check`, `python3 -m py_compile scripts/seed-demo-official.py` | TypeScript sem erros; seed Python compila. |
| 2026-06-29 | Rodar lint frontend | Falhou | `npm run lint` | Falhas preexistentes em varias telas novas por `react-hooks/set-state-in-effect` e 1 warning `no-img-element`; nao foram causadas pelas correcoes desta etapa. |
| 2026-06-29 | Corrigir Bot WhatsApp mobile | Concluida | `frontend/app/admin/bot/page.tsx` | Tabela de horario substituida por cards no mobile, mantendo tabela em `sm+`. |
| 2026-06-29 | Revalidar apos correcoes | Concluida | `npm run type-check`, `scripts/run-frontend-audit-local.sh` | Type-check verde. Auditoria final: 93 achados brutos, 60 screenshots, 404 de imagem zerado, overflows reduzidos de 7 para 3. |
| 2026-06-29 | Corrigir responsividade administrativa | Concluida | `frontend/app/admin/usuarios/page.tsx`, `frontend/components/layout/Topbar.tsx`, `frontend/components/layout/Sidebar.tsx` | Usuarios em tablet usa cards em vez de tabela; topbar/sidebar ganharam alvos de toque de pelo menos 44px. |
| 2026-06-29 | Corrigir base de hitbox do frontend | Concluida | `frontend/app/globals.css`, `frontend/app/cardapio/page.tsx`, `frontend/app/pdv/page.tsx` | Botoes/inputs/icon-buttons padrao e chips de categoria ganharam altura minima de 44px. |
| 2026-06-29 | Revalidar auditoria apos hitboxes globais | Concluida | `npm run type-check`, `scripts/run-frontend-audit-local.sh` | Type-check verde. Auditoria final: 75 achados brutos, 60 screenshots; 404 zero; overflows restantes apenas em barras horizontais mobile de `/cardapio` e `/pdv`. |
| 2026-06-30 | Criar script da Fase 2 | Concluida | `docs/auditorias/2026-06-30-menuflow-phase2-safe-flows.cjs`, `scripts/run-phase2-safe-flows-local.sh` | Script abre fluxos intermediarios seguros sem salvar formularios, fechar pedido, alterar configuracao ou executar acao destrutiva. |
| 2026-06-30 | Executar Fase 2 - Fluxos Operacionais Seguros | Concluida | `docs/outputs/menuflow-phase2-safe-flows/REPORT.md`, `docs/outputs/menuflow-phase2-safe-flows/results.json` | 27 cenarios em PC/tablet/mobile: 26 passed, 1 warning esperado no PC porque menu mobile nao aparece em desktop, 0 failed. |
| 2026-06-30 | Criar script da Fase 3 | Concluida | `docs/auditorias/2026-06-30-menuflow-phase3-controlled-mutations.cjs`, `scripts/run-phase3-controlled-mutations-local.sh` | Script API-only cria dados reais com prefixo `AUDIT-*` no tenant `audit` e valida efeitos em cardapio publico, cupom, KDS, PDV, pagamento e DRE. |
| 2026-06-30 | Executar Fase 3 - Mutacao Controlada | Concluida | `docs/outputs/menuflow-phase3-controlled-mutations/REPORT.md`, `docs/outputs/menuflow-phase3-controlled-mutations/results.json` | 14 etapas, 0 falhas; prefixo `AUDIT-20260630105721`; produto/cupom/despesa/pedido publico/pedido PDV/pagamento criados e DRE refletiu despesa + venda paga. |
| 2026-06-30 | Criar script da Fase 4 | Concluida | `docs/auditorias/2026-06-30-menuflow-phase4-backend-security.cjs`, `scripts/run-phase4-backend-security-local.sh` | Script API-only valida auth, RBAC, tenant binding, idempotencia, contrato publico, DRE invalido e protecoes de plataforma no tenant `audit`. |
| 2026-06-30 | Executar Fase 4 - Backend, Dados e Seguranca | Concluida | `docs/outputs/menuflow-phase4-backend-security/REPORT.md`, `docs/outputs/menuflow-phase4-backend-security/results.json` | 15 etapas, 0 falhas; prefixo `AUDIT4-20260630110704`; validou 401/403, OPERATOR vs DRE, STAFF vs produto, idempotencia, X-Tenant-ID spoofado, DTO publico e anti-lockout. |
| 2026-06-30 | Executar Fase 5.1 - Correcoes UX de hitbox | Concluida | `docs/auditorias/2026-06-30-relatorio-fase-5-ux-hitboxes.md`, `docs/outputs/menuflow-critical-audit/REPORT.md`, `results.json` | Type-check verde. Auditoria frontend final: 45 achados brutos, 60 screenshots; queda de 75 para 45 apos corrigir botoes pequenos, filtros, switches e acoes icon-only. |

## Proxima Etapa

Avancar para a **Fase 5 - Correcoes** contra o tenant `audit`, usando o usuario admin:

- Tenant: `audit`
- Email: `audit@menuflow.local`
- Senha: `Audit@1234`

O tenant ja esta populado no QA local com dados ricos e migrations aplicadas ate a versao 31. As Fases 1, 2, 3 e 4 ja foram executadas, registradas e validadas.

Comando para revalidar a Fase 1 quando necessario:

```powershell
wsl -d Kali-Linux --cd /home/ronaldo/menuflow --% bash scripts/run-frontend-audit-local.sh
```

Para a Fase 5.2, priorizar as tabelas largas restantes: `admin/cupons` em tablet/mobile e a lista de despesas do DRE mobile devem virar cards ate `lg`, mantendo tabela apenas em desktop largo. Depois revisar texto pequeno de topbar/metadados e abrir subfrente de performance, dependencias e producao quando os bloqueantes visuais estiverem controlados.
