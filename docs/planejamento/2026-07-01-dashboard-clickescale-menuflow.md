# Plano - Dashboard MenuFlow inspirado no ClickEscale

Data: 2026-07-01
Status: planejamento salvo apos inspecao visual do ClickEscale e leitura do repo MenuFlow.
Escopo: frontend adm responsivo, agregacoes backend e graficos de gestao para PC, tablet e celular.

## 1. Objetivo

Criar no MenuFlow uma tela inicial de gestao parecida em utilidade com o dashboard do ClickEscale, mas adaptada ao diferencial do MenuFlow: restaurante operando de ponta a ponta, de cardapio/link ate pedido pago, caixa, cliente, recompra e financeiro.

Decisao de produto: o dashboard deve virar a primeira tela do administrador/gerente. A rota `/` hoje redireciona autenticado para `/cardapio`; o alvo e trocar para `/dashboard` ou `/admin/dashboard` quando a tela estiver pronta.

## 2. Evidencia observada no ClickEscale

Tela: `https://app.clickescale.com/dashboard`
Empresa vista: `BlackBurguerCR`

### Navegacao e estrutura

- Sidebar com status da loja, link do cardapio, status iFood e grupos: operacao, cardapio digital, marketing, estoque, financeiro e configuracoes.
- Topbar com saudacao, periodo, status da loja, status iFood, som/notificacoes e entrada para `Click IA`.
- Abas principais do dashboard:
  - Visao Geral
  - Pedidos
  - Clientes
  - Marketing

### Conteudo por aba

Visao Geral:

- Faturamento total
- Numero de pedidos
- Ticket medio
- Clientes novos
- Evolucao de vendas
- Top 5 produtos
- Distribuicao por canal
- Resumo financeiro: receita liquida, taxas, saldo da carteira e status do caixa

Pedidos:

- Faturamento
- Numero de pedidos
- Ticket medio
- Evolucao das vendas
- Produtos mais vendidos
- Acessos
- Cupons usados

Clientes:

- Total de clientes
- Novos no mes
- LTV medio
- Clientes recorrentes
- Evolucao mensal
- Frequencia de compras
- Distribuicao geografica

Marketing:

- Visao por empresa/periodo
- Gasto versus cliques
- Evolucao diaria do investimento e cliques no link

## 3. Estado atual verificado no MenuFlow

Rotas frontend existentes:

- Operacao: `/pdv`, `/kds`, `/mesas`, `/caixa`, `/caixa/historico`
- Cardapio/catalogo: `/admin/cardapio`, `/cardapio`
- Gestao: `/admin/usuarios`, `/admin/entregadores`, `/configuracoes`
- Growth/cliente: `/admin/fidelidade`, `/admin/rfv`, `/admin/campanhas`, `/admin/tracking`, `/admin/conversoes`, `/admin/carrinhos`, `/admin/bot`
- Financeiro: `/financeiro/dre`

Backend ja tem fontes importantes:

- `DreController` e `DreService`: receita bruta/liquida, taxas, CMV, lucro, margem, pedidos por canal e metodo de pagamento.
- `CashSessionController`: caixa atual, abertura, fechamento e historico.
- `TrackingController`: links rastreaveis e resumo de cliques.
- `CampaignController`: campanhas e disparos.
- `CartSessionController`: carrinhos abandonados.
- `RfvController`: segmentos RFV.
- `OrderController`, `PdvController`, `KdsController`, `TableController`, `CustomerController`, `LoyaltyController`, `DeliveryController`, `DriverController`: base operacional para pedidos, cozinha, mesas, clientes, fidelidade e entrega.

Lacuna principal: falta uma camada agregadora de dashboard e falta uma tela de entrada que amarre essas informacoes. Hoje os modulos existem separados.

## 4. Proposta funcional

### 4.1 Rota e entrada

Criar `/dashboard` como pagina adm padrao e adicionar item `Dashboard` no topo da sidebar.

Depois da entrega validada, alterar `frontend/app/page.tsx` para redirecionar autenticado para `/dashboard`.

### 4.2 Layout base

Desktop:

```text
Topbar: Restaurante / Dashboard     periodo     loja aberta/fechada     iFood/WhatsApp     IA

Sidebar fixa | Conteudo
             | [Visao Geral] [Pedidos] [Clientes] [Marketing]
             |
             | KPI 1 | KPI 2 | KPI 3 | KPI 4
             |
             | Grafico principal 2/3 largura      Painel operacional 1/3
             |
             | Top produtos | Canais | Caixa/financeiro | Alertas
```

Tablet:

- Sidebar recolhida ou drawer.
- KPIs em 2 colunas.
- Grafico principal em largura total.
- Cards secundarios em 2 colunas.

Celular:

- Topbar compacta.
- Filtro de periodo em sheet.
- Tabs com rolagem horizontal clara.
- KPIs em 1 coluna ou carrossel horizontal com cards de 44px+ de alvo.
- Graficos empilhados e tabelas virando cards.

### 4.3 Abas do MenuFlow

Visao Geral:

- Faturamento confirmado no periodo.
- Pedidos pagos/concluidos.
- Ticket medio.
- Margem liquida ou lucro estimado.
- Evolucao de vendas por dia/hora.
- Top produtos por receita e quantidade.
- Pedidos por canal: PDV, mesa, delivery, cardapio digital, iFood/OpenDelivery quando disponivel.
- Resumo financeiro: receita liquida, taxas, CMV, despesas, lucro, status do caixa.
- Alertas operacionais: pedidos atrasados no KDS, caixa fechado, mesas abertas, Pix pendente, estoque/insumo critico quando o modulo estiver ativo.

Pedidos:

- Pedidos por status.
- Tempo medio de preparo.
- Pedidos atrasados.
- Produtos mais vendidos.
- Cupons usados.
- Origem do pedido/canal.
- Acoes rapidas para PDV, KDS, Mesas e Caixa.

Clientes:

- Total de clientes.
- Clientes novos.
- Recorrencia.
- LTV medio.
- Segmentos RFV.
- Clientes inativos para reativacao.
- Fidelidade: pontos/resgates/recompensas.
- Bairro/regiao quando houver dado de entrega.

Marketing:

- Cliques por link e campanha.
- Carrinhos abandonados.
- Conversoes disparadas para Meta/Google.
- Campanhas WhatsApp ativas/pausadas.
- Cupom por receita/pedidos.
- Funil: clique -> carrinho -> pedido -> `payment_paid`.
- ROAS/CAC apenas quando houver custo de campanha confiavel. Sem custo, mostrar conversao e receita atribuida, nao inventar ROAS.

## 5. Design visual

Direcao: dashboard SaaS operacional, leve e denso. Nada de landing page. O dono do restaurante precisa bater o olho e decidir o que fazer.

Regras visuais:

- Fundo `bg-bg-secondary`, cards `bg-bg-primary`, borda sutil `border-border-light`.
- Cards com raio ate 8px ou seguindo o design system atual, evitando card dentro de card.
- Verde somente para sucesso/positivo/ativo; vermelho para problema/fechado/atrasado; amarelo para atencao. Nunca usar cor como unico sinal: sempre icone + texto.
- Tipografia compacta: KPI grande, rotulo pequeno, variacao/comparacao discreta.
- Graficos:
  - Linha para evolucao temporal.
  - Barras horizontais para top produtos.
  - Barras/stack para canais e status.
  - Tabela auditavel ou lista detalhada quando o numero financeiro precisa fechar.
  - Evitar pizza com muitas fatias, 3D e eixo truncado.
- Estados obrigatorios: loading, vazio util, erro com acao, sucesso/normal.
- Acessibilidade: contraste AA, foco visivel, alvos de toque com 44px+ no web responsivo.

## 6. Backend proposto

Criar modulo `dashboard` no backend:

```text
GET /api/v1/dashboard/overview?from=YYYY-MM-DD&to=YYYY-MM-DD
GET /api/v1/dashboard/orders?from=YYYY-MM-DD&to=YYYY-MM-DD
GET /api/v1/dashboard/customers?from=YYYY-MM-DD&to=YYYY-MM-DD
GET /api/v1/dashboard/marketing?from=YYYY-MM-DD&to=YYYY-MM-DD
```

DTOs sugeridos:

- `DashboardKpiResponse`
- `DashboardSeriesPoint`
- `DashboardTopProduct`
- `DashboardChannelBreakdown`
- `DashboardFinancialSummary`
- `DashboardOperationalAlert`
- `DashboardOverviewResponse`
- `DashboardOrdersResponse`
- `DashboardCustomersResponse`
- `DashboardMarketingResponse`

Primeira versao deve reaproveitar `DreService.compute`, `CashSessionService.current`, `TrackingService.getSummary`, `CampaignService`, `CartRecoveryService` e repositorios existentes. Evitar SQL solto duplicado quando ja houver servico com regra de negocio.

## 7. Frontend proposto

Criar:

```text
frontend/app/dashboard/page.tsx
frontend/components/dashboard/DashboardShell.tsx
frontend/components/dashboard/DashboardTabs.tsx
frontend/components/dashboard/KpiCard.tsx
frontend/components/dashboard/ChartCard.tsx
frontend/components/dashboard/FinancialSummary.tsx
frontend/components/dashboard/OperationalAlerts.tsx
frontend/lib/dashboard-api.ts
```

Biblioteca grafica recomendada:

- Recharts se ja estiver instalado ou aceito no frontend.
- Caso nao esteja instalado, iniciar com componentes leves e tabelas/cards, deixando Recharts para a fase de graficos reais.

## 8. Plano por fases

### Fase 1 - Casca visual e navegacao

Objetivo: entregar a tela `/dashboard` responsiva com layout real, tabs, filtros, cards e estados vazios.

Tarefas:

- Adicionar item `Dashboard` na sidebar.
- Criar rota `/dashboard`.
- Implementar top area com filtro de periodo, saudacao/status e atalho para IA.
- Criar tabs Visao Geral, Pedidos, Clientes e Marketing.
- Implementar componentes de KPI, card de grafico, lista top, alerta e resumo financeiro com dados mockados ou vazios controlados.
- Validar PC, tablet e celular.

Etapa concluida neste planejamento: benchmark ClickEscale inspecionado e estrutura visual definida.

### Fase 2 - Dados reais minimos

Objetivo: ligar Visao Geral aos dados ja existentes.

Tarefas:

- Criar `DashboardController` e `DashboardService`.
- Reaproveitar DRE para faturamento, ticket medio, pedidos por canal/metodo e resumo financeiro.
- Reaproveitar caixa atual para status do caixa.
- Consultar pedidos recentes/top produtos via repositorio.
- Expor `GET /dashboard/overview`.
- Ligar frontend ao endpoint com loading/erro/vazio.

### Fase 3 - Pedidos e operacao

Objetivo: transformar o dashboard em painel operacional do dia.

Tarefas:

- Agregar status dos pedidos.
- Trazer atrasos de cozinha/KDS.
- Trazer mesas abertas/ocupadas.
- Expor tempo medio de preparo quando houver dado confiavel.
- Criar acoes rapidas para PDV, KDS, Mesas e Caixa.

### Fase 4 - Clientes e recompra

Objetivo: usar RFV, fidelidade e clientes para mostrar crescimento real.

Tarefas:

- Total de clientes e novos no periodo.
- Recorrencia e LTV.
- Segmentos RFV.
- Clientes inativos.
- Fidelidade: pontos/resgates.
- Distribuicao por bairro quando endereco existir.

### Fase 5 - Marketing e funil

Objetivo: aproximar ClickEscale, mas com mais verdade financeira.

Tarefas:

- Cliques por tracking link.
- Carrinhos abandonados por status.
- Campanhas por status.
- Conversoes enviadas/sucesso/erro.
- Funil clique -> carrinho -> pedido -> `payment_paid`.
- Receita atribuida por campanha/link.
- ROAS/CAC somente quando custo da campanha existir.

### Fase 6 - IA do dono

Objetivo: criar entrada visual para o copiloto sem deixar IA inventar numero.

Tarefas:

- Botao `MenuFlow IA` no dashboard/topbar.
- Painel lateral com perguntas prontas:
  - "Por que vendi menos hoje?"
  - "Qual produto devo promover?"
  - "Quais clientes devo reativar?"
  - "Meu caixa fecha com o DRE?"
- IA deve receber numeros ja calculados por servicos deterministas.
- Qualquer acao de campanha/desconto/publicacao vira rascunho e exige aprovacao humana.

### Fase 7 - Qualidade e auditoria

Objetivo: nao repetir retrabalho de responsividade.

Tarefas:

- Playwright visual em 390, 768/834 e 1366/1440.
- Checar `scrollWidth > clientWidth`.
- Checar hitboxes reais.
- Checar contraste de cards/badges.
- Testar loading, vazio e erro.
- Testar admin e manager; cashier deve ver apenas o que for permitido.
- Registrar evidencias em `docs/outputs/dashboard-audit`.

## 9. Definicao de pronto

- `/dashboard` funciona em PC, tablet e celular sem overflow indevido.
- Admin entra e entende em ate 5 segundos: quanto vendeu, quantos pedidos teve, como esta o caixa e onde ha problema.
- Numeros financeiros batem com DRE/caixa.
- Marketing fecha no evento `payment_paid` quando houver atribuicao.
- Estados vazios orientam o dono, sem tela morta.
- Graficos tem tabela/lista de apoio quando o valor exato importa.
- Nenhum dado sensivel e exposto sem necessidade.
- Plano e evidencias salvos na pasta `docs/`.

## 10. Registro de etapas

- 2026-07-01: ClickEscale dashboard inspecionado na conta aberta pelo usuario.
- 2026-07-01: Abas Visao Geral, Pedidos, Clientes e Marketing mapeadas.
- 2026-07-01: Rotas frontend do MenuFlow mapeadas.
- 2026-07-01: Controllers/servicos principais do backend mapeados.
- 2026-07-01: Plano grafico, funcional e tecnico salvo neste arquivo.
- 2026-07-01: Fase 1 iniciada no frontend: rota `/dashboard`, tabs, KPIs, cards, graficos, alertas, sidebar e redirecionamento inicial implementados.
- 2026-07-01: Validacao tecnica da Fase 1: `npm run type-check`, `npm run lint` e `npm run build` passaram; Next listou `/dashboard` como rota gerada.
- 2026-07-01: Validacao visual local em `http://127.0.0.1:3011/dashboard`: PC 1366, tablet 834 e mobile 390 sem overflow horizontal; evidencias salvas em `docs/outputs/dashboard-audit/`.

## 11. Atualizacao 2026-07-08 — estado real (via git, main @2e474c4)

> O registro acima congelou em 2026-07-01. Reconciliacao com o codigo entregue ate 08/07:

- **Fase 1 (casca `/dashboard`) — UNICA entregue.** O shell esta em `main`; Fases 2–7 (dados reais, pedidos/operacao, clientes/recompra, marketing/funil, IA do dono, qualidade) **NAO iniciadas**.
- Motivo do parking: o esforco de 02–08/07 pivotou para o **app mobile (M1 KDS → M4 Caixa)**, **delivery/motoboy (Fase 6.2)**, **growth (cupons/fidelidade/RFV)** e **super-admin (F1–F3)**.
- **Proxima fatia natural: Fase 2 — dados reais minimos** (ligar KPIs/cards aos endpoints do backend).
