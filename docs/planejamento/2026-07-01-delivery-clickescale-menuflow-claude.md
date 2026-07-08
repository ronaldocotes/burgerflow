# Plano de execucao para Claude - MenuFlow / Delivery

Data: 2026-07-01

Objetivo: criar uma central `/delivery` no MenuFlow inspirada no ClickEscale, mas alinhada ao dominio real do MenuFlow. A tela deve ser a central de despacho de entregas, sem substituir `/pedidos` e sem duplicar `/admin/entregadores`.

## 1. Decisao de produto

O MenuFlow deve ter algo semelhante ao ClickEscale `/delivery`, mas com separacao clara de responsabilidades:

- `/pedidos`: central geral de pedidos.
- `/delivery`: despacho operacional de rua.
- `/admin/entregadores`: cadastro, remuneracao e acertos de entregadores.
- `/dashboard`: visao executiva do dono/gerente.

Recomendacao: implementar `/delivery` como tela operacional para admin/gerente/operador acompanharem pedidos de entrega, motoboys em turno, atribuicao, status e mapa. Integracao iFood/OpenDelivery deve aparecer como indicador futuro, nao como dependencia da primeira entrega.

## 2. O que foi verificado

### ClickEscale `/delivery`

Verificado no navegador autenticado em `https://app.clickescale.com/delivery`:

- Header com titulo `Delivery`.
- Indicador `Status da loja: Fechado`.
- Botao/indicador de `Status iFood`.
- Mapa com Leaflet/OpenStreetMap/CARTO.
- Camadas visuais de area/raio de entrega.
- Botao `Centralizar na loja`.
- Secao `Motoboys`.
- Tabela com colunas:
  - Nome
  - Placa
  - Diaria
  - Valor por Entrega
  - Valor por KM
  - Forma de pagamento
- Estado vazio: `Nenhum motoboy cadastrado`.
- Modal `Adicionar Motoboy` com:
  - nome completo
  - email
  - telefone
  - senha

Interpretacao: a tela ClickEscale mistura duas coisas: area/mapa de entrega e equipe de motoboys. No MenuFlow, a administracao financeira de motoboys ja tem tela propria, entao `/delivery` deve focar no despacho.

### MenuFlow - backend existente

Arquivos verificados:

- `backend/src/main/kotlin/com/menuflow/controller/DeliveryController.kt`
- `backend/src/main/kotlin/com/menuflow/controller/DriverController.kt`
- `backend/src/main/kotlin/com/menuflow/dto/DeliveryDtos.kt`
- `backend/src/main/kotlin/com/menuflow/model/Order.kt`
- `backend/src/main/kotlin/com/menuflow/model/TenantConfig.kt`

Contratos ja existentes:

- `GET /api/v1/delivery/orders/active`
- `GET /api/v1/delivery/drivers`
- `POST /api/v1/delivery/orders/{orderId}/assign`
- `PUT /api/v1/delivery/orders/{orderId}/status`
- `POST /api/v1/delivery/drivers/{id}/shift`
- `POST /api/v1/delivery/location`
- `POST /api/v1/delivery/offers/{id}/accept`
- `POST /api/v1/delivery/offers/{id}/reject`
- `GET /api/v1/delivery/orders/my`
- `GET /api/v1/drivers`
- `GET /api/v1/drivers/{driverId}/config`
- `PUT /api/v1/drivers/{driverId}/config`
- `GET /api/v1/drivers/settlements`
- `POST /api/v1/drivers/settlements/open`
- `POST /api/v1/drivers/settlements/{id}/close`

Dados ja modelados:

- Pedido tem `driverId` e `deliveryStatus`.
- Pedido tem origem externa `externalOrigin`, `externalOrderId`, `externalDisplayId`.
- Pedido tem campos de endereco de entrega:
  - `deliveryRecipientName`
  - `deliveryPhone`
  - `deliveryCep`
  - `deliveryStreet`
  - `deliveryNumber`
  - `deliveryComplement`
  - `deliveryNeighborhood`
  - `deliveryCity`
  - `deliveryReference`
  - `deliveryLat`
  - `deliveryLng`
  - `deliveryGeocodeSource`
- Configuracao do tenant tem:
  - `deliveryMode`
  - `autoAssignEnabled`
  - `offerTimeoutSeconds`
  - `maxOfferRadiusKm`
  - `deliveryBaseFeeCents`
  - `deliveryFeePerKmCents`
- DTO de driver ja tem:
  - `activeShift`
  - `lastLat`
  - `lastLng`
  - `lastLocationAt`
  - `batteryPct`

Lacuna confirmada para frontend: `DeliveryOrderResponse` ainda e pequeno para a tela rica. Ele retorna `orderId`, `orderNumber`, `driverId`, `deliveryStatus`, `totalCents`, `tableNumber`, `updatedAt`. Para mapa/endereco/SLA, sera necessario ampliar o DTO sem quebrar contrato.

### MenuFlow - frontend existente

Arquivos verificados:

- `frontend/app/pedidos/page.tsx`
- `frontend/app/admin/entregadores/page.tsx`
- `frontend/components/layout/Sidebar.tsx`
- `frontend/components/layout/Topbar.tsx`

Rotas atuais relevantes:

- `/dashboard`
- `/pedidos`
- `/pdv`
- `/kds`
- `/mesas`
- `/caixa`
- `/admin/entregadores`

Ainda nao existe `frontend/app/delivery`.

## 3. Estado do repositorio e cuidado obrigatorio

Antes de executar, Claude deve rodar:

```bash
cd /home/ronaldo/menuflow
git status --short
```

Estado observado em 2026-07-01:

- Existem muitas alteracoes locais de backend em andamento.
- Essas alteracoes parecem ser de outra frente/sessao.
- Nao reverter nada.
- Nao fazer `git reset`.
- Nao fazer checkout destrutivo.
- Nao incluir mudancas de backend no commit se a tarefa executada for apenas frontend.

Regra de staging:

- Se implementar so a tela `/delivery`, stagear apenas:
  - `frontend/app/delivery/page.tsx`
  - `frontend/components/layout/Sidebar.tsx`
  - `frontend/components/layout/Topbar.tsx`
  - docs/evidencias criadas para delivery
- Nao stagear:
  - `backend/**`, salvo se a tarefa explicitamente incluir backend
  - `frontend/next-env.d.ts`, salvo se gerado e revisado de forma intencional
  - `backend/.kotlin/`

## 4. Conselho de agentes

### Construtor

Decisao integrada: fazer `/delivery` como modulo operacional dentro do monolito modular existente. O modulo conversa com pedidos, entregadores, financeiro/acertos e configuracao do tenant. Nao criar microservico, nao criar produto separado.

### Nick

UX deve ser por tarefa operacional, nao por tela decorativa. O usuario de restaurante precisa responder rapido:

- quais entregas estao pendentes?
- qual esta atrasada?
- qual motoboy esta livre?
- quem pegou cada pedido?
- para onde esta indo?
- qual acao tomar agora?

Desktop deve priorizar densidade e comparacao. Tablet deve empilhar sem perder contexto. Celular deve virar cards e acoes grandes.

### Craudio

Backend deve ser fonte da regra de negocio:

- status permitido
- atribuicao
- reautorizacao do pedido/entregador no tenant
- calculo de taxa
- auto-assign
- auditoria

Frontend nao deve decidir regra critica.

### Centuriao

Riscos:

- IDOR ao atribuir pedido a entregador de outro tenant.
- DRIVER aceitando oferta de outro driver.
- Exposicao de endereco completo em tela/grupo indevido.
- Coordenada de motoboy como dado sensivel.

Plano deve minimizar PII e exigir backend fail-closed.

### Testador

Entrega so conta se passar em:

- type-check
- lint
- build
- teste visual PC/tablet/mobile
- sem overflow horizontal
- estados loading/vazio/erro/sucesso
- acoes principais com feedback

## 5. Escopo recomendado para Claude executar agora

### Fase 1 - `/delivery` frontend operacional

Objetivo: criar uma primeira tela util e responsiva usando os contratos existentes, com degradacao graciosa enquanto o backend ainda esta sendo editado.

Nao fazer nesta fase:

- Nao implementar Leaflet completo se isso exigir dependencia nova e risco de SSR.
- Nao mexer em migrations.
- Nao mexer em auto-assign.
- Nao mexer em login de motoboy.
- Nao remover `/admin/entregadores`.

Fazer:

1. Criar `frontend/app/delivery/page.tsx`.
2. Adicionar item `Delivery` ou `Entregas` na sidebar em `OPERAÇÃO`.
3. Adicionar titulo `/delivery` na topbar.
4. Consumir:
   - `GET /delivery/orders/active`
   - `GET /delivery/drivers`
5. Permitir atribuicao manual:
   - `POST /delivery/orders/{orderId}/assign`
6. Permitir avancar status de entrega:
   - `PUT /delivery/orders/{orderId}/status`
7. Permitir ligar/desligar turno se endpoint estiver funcionando:
   - `POST /delivery/drivers/{id}/shift`
8. Mostrar mapa operacional sem dependencia nova:
   - painel visual com loja no centro
   - raio/area aproximada com CSS
   - marcadores se houver `lastLat/lastLng`
   - estado "sem coordenadas ainda" se nao houver dados
9. Criar fila de entregas com cards.
10. Criar painel de detalhe do pedido selecionado.
11. Criar secao de motoboys em turno.
12. Criar estados de loading/vazio/erro/sucesso.
13. Criar UX responsiva:
   - PC: mapa + fila + detalhe em layout de duas/ tres areas.
   - tablet: mapa no topo, fila e detalhe empilhados.
   - celular: cards, abas `Mapa`, `Entregas`, `Motoboys`, detalhe em sheet/modal.

Resultado esperado da Fase 1:

- O admin consegue abrir `/delivery`.
- Ve entregas ativas.
- Ve motoboys ativos/em turno.
- Atribui uma entrega a um motoboy.
- Avanca status de entrega.
- Entende quando nao ha entregas ou quando o backend esta indisponivel.

## 6. Layout recomendado

### Desktop >= 1280px

```text
Header: Delivery | status loja | status iFood futuro | atualizar

KPI row:
[Entregas ativas] [Aguardando motoboy] [Em rota] [Atrasadas] [Motoboys em turno]

Main:
┌───────────────────────────────┬──────────────────────────────┐
│ Mapa operacional              │ Fila de entregas             │
│ - loja no centro              │ [chips status] [busca]       │
│ - raio maximo                 │ #1023 Aguardando motoboy     │
│ - motoboys com GPS            │ #1022 Em rota                │
│ - pedidos com geo se houver   │ #1021 Entregue               │
├───────────────────────────────┴──────────────────────────────┤
│ Detalhe selecionado                                           │
│ cliente/endereco resumido | itens | pagamento | motoboy | acoes│
└───────────────────────────────────────────────────────────────┘

Rodape da area:
Motoboys em turno: cards compactos com nome, placa, bateria, ultima localizacao.
```

### Tablet 768-1024px

```text
Header
KPI row em grid 2x/3x
Mapa
Abas: Entregas | Motoboys
Lista em cards
Detalhe em painel abaixo ou sheet
```

### Celular 390-430px

```text
Header compacto
KPI scroller horizontal com chips de 44px+
Tabs: Entregas | Mapa | Motoboys
Cards grandes
Botao de acao principal sempre visivel no detalhe
Sem tabela
Sem texto espremido
```

## 7. Componentes sugeridos

Manter simples na Fase 1, em um unico arquivo se o padrao local assim preferir. Extrair somente se ficar grande demais.

Componentes internos possiveis:

- `DeliveryKpis`
- `DeliveryMapPanel`
- `DeliveryOrderCard`
- `DeliveryDetailPanel`
- `DriverCard`
- `AssignDriverModal`
- `StatusActionButtons`
- `EmptyState`
- `ErrorState`

Tipos TypeScript locais:

```ts
type DeliveryStatus =
  | 'WAITING_ASSIGNMENT'
  | 'ASSIGNED'
  | 'PICKED_UP'
  | 'DELIVERED'
  | 'FAILED'
  | 'CANCELLED'

type DeliveryOrderResponse = {
  orderId: string
  orderNumber: string
  driverId: string | null
  deliveryStatus: DeliveryStatus | null
  totalCents: number
  tableNumber: string | null
  updatedAt: string
}

type DriverResponse = {
  id: string
  name: string
  phone: string
  licensePlate: string | null
  active: boolean
  activeShift: boolean
  lastLat: number | null
  lastLng: number | null
  lastLocationAt: string | null
  batteryPct: number | null
}
```

Se o backend real usar outros nomes de enum, Claude deve ler `backend/src/main/kotlin/com/menuflow/model/Order.kt` e ajustar antes de codar.

## 8. UX e regras visuais

Obrigatorio:

- Usar tokens existentes: `bg-bg-primary`, `bg-bg-secondary`, `border-border-light`, `text-text-primary`, `text-text-secondary`, `primary-700`.
- Nao usar cor hardcoded fora de casos inevitaveis.
- Status nunca apenas por cor: usar texto + icone/ponto.
- Atraso deve ser visivel, mas nao pintar o card inteiro.
- Botao principal deve ter `min-h-11` ou area real >= 44px.
- Em mobile, nada de tabela.
- Em tablet, nao espremer 6 colunas.
- Erro deve ter `role="alert"`.
- Modal/sheet deve usar `role="dialog"`, `aria-modal`, `aria-labelledby` e `useModalA11y`.
- Loading com skeleton, nao tela branca.
- Vazio com acao clara:
  - "Nenhuma entrega ativa"
  - atalho para `/pedidos` ou `/pdv`
- Backend indisponivel:
  - mensagem amigavel
  - botao `Tentar novamente`

## 9. Acoes e regras de negocio

### Atribuir motoboy

Fluxo:

1. Usuario seleciona pedido.
2. Clica `Atribuir`.
3. Modal lista motoboys ativos/em turno primeiro.
4. Usuario escolhe motoboy.
5. Front chama `POST /delivery/orders/{orderId}/assign`.
6. Recarrega fila e mostra feedback.

Nao permitir no front:

- botao ativo sem motoboy escolhido.
- duplo submit.
- atribuir se pedido ja esta entregue/cancelado.

Mas a regra final deve ser do backend.

### Avancar status

Fase 1 deve mostrar acoes por status:

- Sem motoboy: `Atribuir`.
- Atribuido: `Saiu para entrega`.
- Em rota/coletado: `Marcar entregue`.
- Falha: `Registrar problema` fica para fase posterior se nao houver endpoint claro.

Claude deve confirmar o enum real de `DeliveryStatus` antes de implementar os nomes.

### Turno do motoboy

Se `POST /delivery/drivers/{id}/shift` responder corretamente:

- permitir alternar turno para admin/manager/operator.

Se nao responder:

- mostrar status somente leitura e registrar pendencia no plano.

## 10. Backend - Fase 1B se necessario

Se o frontend ficar pobre por falta de dados, fazer uma alteracao aditiva e pequena no backend.

Arquivo provavel:

- `backend/src/main/kotlin/com/menuflow/dto/DeliveryDtos.kt`

Ampliar `DeliveryOrderResponse` com campos opcionais:

```kotlin
val externalOrigin: String,
val externalDisplayId: String?,
val deliveryRecipientName: String?,
val deliveryPhone: String?,
val deliveryNeighborhood: String?,
val deliveryCity: String?,
val deliveryReference: String?,
val deliveryLat: Double?,
val deliveryLng: Double?,
val deliveryFeeCents: Long,
val salesChannel: SalesChannel?,
val paymentStatus: PaymentStatus?,
```

Regras:

- Nao retornar endereco completo no card da fila se nao for necessario.
- No detalhe, mostrar endereco completo somente para admin/manager/operator.
- Se expor telefone, manter utilidade operacional e evitar dados sensiveis em prints publicos.
- DTO aditivo nao deve quebrar clientes existentes.

Backend tests:

- teste de serializacao/contrato se houver padrao existente.
- teste de IDOR/driver owner se mexer em ofertas.
- `./gradlew test --no-daemon --stacktrace`.

## 11. Mapa

### Fase 1 - mapa visual sem dependencia nova

Implementar um painel visual que:

- mostra um ponto de loja no centro;
- mostra raio maximo usando `maxOfferRadiusKm` quando disponivel ou texto "Raio configuravel";
- plota motoboys com coordenadas recentes;
- mostra estado vazio se nao houver coordenadas;
- nao promete rota real.

Texto sugerido:

- "Mapa operacional"
- "Aguardando localizacao dos motoboys"
- "A rota real entra na proxima fase com Leaflet"

### Fase 2 - Leaflet real

So depois da Fase 1 validada:

1. Verificar `frontend/package.json`.
2. Preferir `leaflet` imperativo em componente client-only para reduzir risco com React/Next.
3. Carregar mapa com dynamic import/efeito client-only.
4. Importar CSS do Leaflet com cuidado no App Router.
5. Usar OpenStreetMap/CARTO.
6. Centralizar na loja.
7. Desenhar:
   - raio de entrega;
   - markers de motoboys;
   - markers de pedidos com `deliveryLat/deliveryLng`;
   - popup sem PII excessiva.

Nao fazer roteirizacao real ainda. Roteirizacao por ruas entra depois com OSRM/Google Routes, se houver necessidade comercial.

## 12. Arquivos esperados na Fase 1

Criar:

- `frontend/app/delivery/page.tsx`
- `docs/outputs/delivery-audit/` com screenshots de validacao

Alterar:

- `frontend/components/layout/Sidebar.tsx`
- `frontend/components/layout/Topbar.tsx`
- este plano, atualizando o log de execucao

Opcional se decidir extrair:

- `frontend/app/delivery/components/*`
- `frontend/app/delivery/types.ts`

Evitar nesta fase:

- migrations novas
- alteracoes grandes em `DeliveryService`
- libs de mapa sem necessidade
- alteracoes em mobile

## 13. Checklist de execucao para Claude

### Preflight

- [ ] Rodar `git status --short`.
- [ ] Confirmar quais arquivos estao sujos.
- [ ] Ler `DeliveryController.kt`.
- [ ] Ler `DeliveryDtos.kt`.
- [ ] Ler `frontend/app/pedidos/page.tsx` para reaproveitar padrao de fetch/erro/layout.
- [ ] Ler `frontend/app/admin/entregadores/page.tsx` para reaproveitar tipos/driver/remuneracao.
- [ ] Confirmar enums reais de `DeliveryStatus`.

### Implementacao

- [ ] Criar `/delivery`.
- [ ] Adicionar menu.
- [ ] Adicionar topbar title.
- [ ] Buscar entregas ativas.
- [ ] Buscar motoboys.
- [ ] Criar skeleton.
- [ ] Criar empty state.
- [ ] Criar error state.
- [ ] Criar cards responsivos de entrega.
- [ ] Criar mapa operacional.
- [ ] Criar cards de motoboy.
- [ ] Criar detalhe do pedido.
- [ ] Criar modal de atribuicao.
- [ ] Criar acoes de status.
- [ ] Tratar token ausente redirecionando para `/login`.
- [ ] Evitar overflow horizontal em 390px, 834px e 1366px.

### Validacao

Executar:

```bash
cd /home/ronaldo/menuflow/frontend
npm run type-check
npm run lint
npm run build
```

Se mexer no backend:

```bash
cd /home/ronaldo/menuflow/backend
./gradlew test --no-daemon --stacktrace
```

QA visual:

- [ ] PC 1366px.
- [ ] Tablet 834px.
- [ ] Mobile 390px.
- [ ] `scrollWidth <= clientWidth`.
- [ ] Nenhum texto sobreposto.
- [ ] Botao com hitbox >= 44px.
- [ ] Loading visivel.
- [ ] Erro com `role="alert"`.
- [ ] Empty state com acao clara.
- [ ] Screenshots salvos em `docs/outputs/delivery-audit/`.

### Commit

Se aprovado pelo dono:

```bash
git add frontend/app/delivery/page.tsx frontend/components/layout/Sidebar.tsx frontend/components/layout/Topbar.tsx docs/planejamento/2026-07-01-delivery-clickescale-menuflow-claude.md docs/outputs/delivery-audit
git commit -m "feat: add delivery dispatch workspace"
git push origin main
```

Antes do commit, conferir:

```bash
git diff --cached --stat
```

Nao commitar backend sujo de outra sessao.

## 14. Definition of Done

- [ ] `/delivery` existe e renderiza autenticado.
- [ ] Sidebar mostra entrada operacional.
- [ ] Topbar mostra titulo correto.
- [ ] Lista de entregas ativas carrega via API.
- [ ] Lista de motoboys carrega via API.
- [ ] Atribuicao manual funciona ou mostra erro amigavel se backend negar.
- [ ] Avanco de status funciona ou mostra erro amigavel se backend negar.
- [ ] Mapa operacional nao fica em branco.
- [ ] PC/tablet/mobile sem overflow.
- [ ] Mobile usa cards, nao tabela.
- [ ] Estados loading/vazio/erro/sucesso presentes.
- [ ] `npm run type-check` passa.
- [ ] `npm run lint` passa.
- [ ] `npm run build` passa.
- [ ] Evidencias salvas.
- [ ] Plano atualizado com o que foi feito.

## 15. Prompt curto para abrir no Claude

Use este prompt se for iniciar outra sessao:

```text
Voce esta no repo /home/ronaldo/menuflow. Implemente a Fase 1 do plano docs/planejamento/2026-07-01-delivery-clickescale-menuflow-claude.md.

Objetivo: criar a rota frontend /delivery como central de despacho de entregas, inspirada no ClickEscale /delivery, sem substituir /pedidos e sem mexer no backend sujo de outra sessao.

Antes de codar: rode git status --short, leia DeliveryController.kt, DeliveryDtos.kt, frontend/app/pedidos/page.tsx e frontend/app/admin/entregadores/page.tsx. Nao reverta nada. Nao stageie backend.

Entregar: frontend/app/delivery/page.tsx, menu na Sidebar, titulo na Topbar, estados loading/vazio/erro/sucesso, cards responsivos, mapa operacional sem dependencia nova, lista de motoboys, atribuicao manual, avanco de status e validacao PC/tablet/mobile.

Validar: npm run type-check, npm run lint, npm run build, screenshots em docs/outputs/delivery-audit. So comitar se o dono pedir.
```

## 16. Registro de execucao

| Data | Etapa | Status | Observacao |
| --- | --- | --- | --- |
| 2026-07-01 | Benchmark ClickEscale `/delivery` | Concluida | Tela tem mapa Leaflet, area/raio, status loja/iFood, motoboys e modal de cadastro. |
| 2026-07-01 | Grounding MenuFlow backend/frontend | Concluida | Existem APIs `/delivery`, `/drivers`, tela `/admin/entregadores`, campos de endereco/geo no pedido e config de entrega no tenant. |
| 2026-07-01 | Plano para Claude | Concluida | Este documento define Fase 1 frontend, Fase 1B backend opcional e DoD. |
| 2026-07-01 | Fase 1B — Backend DTO | Concluída |  ampliado com 12 campos (endereço, geocode, canal, pagamento, datas). Serialização por  evita acoplamento cross-módulo. |
| 2026-07-01 | Fase 1 — Frontend /delivery | Concluída |  (1167 linhas): KPIs, mapa operacional CSS/SVG, cards responsivos, skeleton, empty state, erro com role=alert, modal de atribuição (useModalA11y), toggle de turno, avanço de status. |
| 2026-07-01 | Sidebar + Topbar | Concluída | Entrada  em OPERAÇÃO (Truck, ADMIN/MANAGER/STAFF/CASHIER). Topbar mapeia para título 'Entregas'. |
| 2026-07-01 | Validação frontend | PASSOU | tsc --noEmit: 0 erros. eslint: 0 warnings. next build:  compilado como rota estática. |
| 2026-07-01 | Screenshots QA | Parcial | Arquivos PNG salvos em docs/outputs/delivery-audit/ mas renderizaram em branco por limitação WSL networking (standalone server + Playwright). Validação visual pendente em browser real. |
| 2026-07-02 | Dispatch Fases A–E | Concluida | Endereco/pricing/auto-assign (A/B1), WAHA rastreio+cadastro (B2/B3/C1/C2), bot-como-ferramenta (D), OSRM self-hosted custo-zero (E). |
| 2026-07-05 | Fase 6.2 — Backend app motoboy | Concluida | `2b7cbef`: GET /delivery/me, POST /shift, /offers/my, /earnings/my, POST /orders/{id}/status idempotente + PUT /drivers/{id}/user (elo user↔driver, indice unico V35). Auditoria Centuriao aplicada: A1 BOLA, M1 PII/GPS restrito, M2 token 72h (V52), B1 LGPD. |
| 2026-07-05 | Fase 6.2 — App motoboy (React Native) | Concluida | `19baf09`+`fe6d602`: turno, ofertas, entregas, ganhos e GPS; casado com o contrato real de /earnings/my. |
| 2026-07-05 | Fase 6.2 — Frontend vinculo user↔driver | Concluida | `2abc3f2`/`2dbcddb`: /admin/entregadores com Vincular/Desvincular; integrado em release/sprint-6.2 e main. |
| 2026-07-08 | Reconciliacao do registro | — | Estado real confirmado via git (main @2e474c4). Screenshots QA seguem pendentes (limitacao WSL). |
