# Comparativo - Pedidos ClickEscale x MenuFlow

Data: 2026-07-01
Escopo: comparar `https://app.clickescale.com/pedidos` com o estado atual do MenuFlow e propor melhorias de layout e funcionalidade.
Metodo: leitura visual autenticada do ClickEscale em modo somente leitura, inspecao do frontend/backend MenuFlow.

## 1. O que o ClickEscale entrega em Pedidos

Tela principal:

- Busca por cliente ou numero do pedido.
- Botao `Novo Pedido`.
- Botao `Roteirizar`.
- Botao `Historico`.
- Filtros/chips por status:
  - Agendado
  - Novo Pedido
  - Em preparacao
  - Pronto
  - Em entrega
  - Concluido
  - Cancelado
- Layout desktop em duas areas:
  - lista/kanban de pedidos a esquerda;
  - painel de detalhe do pedido a direita.
- Estado vazio: `Nenhum pedido encontrado` + `Selecione um pedido para ver os detalhes`.

Modal `Novo Pedido Manual`:

- Busca central de produto.
- Coluna de categorias.
- Grid de produtos com imagem, nome, preco e sinalizacao de opcoes.
- Painel lateral de pedido com:
  - busca/seleção de cliente;
  - opcao `Fazer pedido sem dados do cliente`;
  - forma de entrega;
  - pedido para agora/agendamento;
  - observacoes;
  - carrinho;
  - total;
  - botao `Pagar`.

Ponto forte: o operador consegue criar um pedido manual sem sair da tela de gestao de pedidos.

## 2. Estado atual do MenuFlow verificado

Frontend:

- Nao existe rota unica `/pedidos`.
- Criacao de pedido vive em `/pdv`.
- Producao/cozinha vive em `/kds`.
- Caixa/fechamento vive em `/caixa`.
- Dashboard recem-criado mostra resumo, mas nao substitui uma central operacional de pedidos.

Backend:

- `OrderController` ja oferece:
  - `GET /orders` com filtro por status e intervalo;
  - `GET /orders/{id}`;
  - `POST /orders` com `Idempotency-Key`;
  - `POST /orders/quote`;
  - `PUT /orders/{id}/status`.
- Isso ja permite construir uma central de pedidos sem criar todo o backend do zero.

KDS:

- Board atual tem colunas `Novos`, `Em preparo`, `Prontos`.
- Cards tem numero, tipo, mesa, tempo decorrido, itens, origem externa e acao de avancar.
- Tem cancelamento com motivo e aviso de conexao.
- E excelente para cozinha, mas nao e uma central de atendimento/gestao.

PDV:

- Ja tem catalogo, categorias, busca, carrinho, cotacao no backend, forma de pagamento e Pix.
- E excelente para lancar venda, mas nao lista/acompanha todos os pedidos existentes.

## 3. Gap principal

O ClickEscale tem uma tela de `Gestao de Pedidos` que unifica acompanhamento + criacao manual + roteirizacao + historico.

No MenuFlow, essas responsabilidades existem em partes, mas estao fragmentadas:

```text
Criar pedido       -> /pdv
Cozinha/status     -> /kds
Financeiro/caixa   -> /caixa
API de pedidos     -> /orders
Central de pedidos -> ainda falta
```

## 4. Recomendacao de produto

Criar rota `/pedidos` ou `/admin/pedidos` como central operacional, sem remover `/pdv` e `/kds`.

Regra de separacao:

- `/pdv`: tela rapida de venda no balcao/caixa.
- `/kds`: tela de cozinha.
- `/pedidos`: tela do gerente/atendente para enxergar, buscar, filtrar, detalhar, editar e resolver pedidos.

## 5. Layout recomendado para MenuFlow

Desktop:

```text
Header: Pedidos        Loja/caixa/status       Novo pedido

Toolbar:
[Buscar pedido, cliente, telefone] [Hoje] [Canal] [Pagamento] [Roteirizar] [Historico]

Status:
[Agendado] [Novo] [Em preparo] [Pronto] [Em entrega] [Concluido] [Cancelado]

Conteudo:
┌ Lista de pedidos / cards compactos ┐ ┌ Detalhe do pedido selecionado ┐
│ #1023  Mesa 4  R$ 52,00  12 min    │ │ Cliente / canal / pagamento   │
│ #1022  Delivery  Pix pendente      │ │ Itens e observacoes           │
│ #1021  iFood  atrasado             │ │ Timeline de status            │
└────────────────────────────────────┘ │ Acoes: avancar, imprimir, ... │
                                       └───────────────────────────────┘
```

Tablet:

- Lista em largura total.
- Detalhe abre em painel lateral/sheet.
- Status em chips com rolagem horizontal clara.

Celular:

- Cards de pedidos em uma coluna.
- Busca fixa no topo.
- Filtro/status em chips.
- Detalhe em bottom sheet/tela cheia.
- Novo pedido abre fluxo em etapas ou reaproveita PDV em modo sheet.

## 6. Funcionalidades prioritarias

### Fase 1 - Central de consulta e detalhe

- Criar `/pedidos`.
- Buscar por numero, cliente e telefone.
- Filtrar por status, periodo e tipo: mesa, retirada, delivery.
- Listar pedidos com cards compactos:
  - numero;
  - status;
  - tempo;
  - canal/origem;
  - cliente/mesa;
  - total;
  - pagamento;
  - alerta de atraso.
- Painel de detalhe:
  - itens;
  - observacoes;
  - endereco/mesa;
  - pagamento;
  - timeline de status;
  - acoes permitidas por papel.

### Fase 2 - Acoes operacionais

- Avancar status.
- Cancelar com motivo.
- Reimprimir/comprovante.
- Marcar pagamento recebido quando permitido.
- Reenviar link de pagamento ou cardapio.
- Enviar/abrir conversa WhatsApp quando houver telefone.

### Fase 3 - Novo pedido manual integrado

- Criar modal/sheet `Novo Pedido`.
- Reaproveitar componentes do `/pdv`:
  - categorias;
  - busca;
  - grid de produtos;
  - personalizacao;
  - carrinho;
  - cotacao no backend;
  - pagamento.
- Adicionar cliente, entrega/agendamento e observacoes no mesmo fluxo.

### Fase 4 - Delivery e roteirizacao

- Botao `Roteirizar` deve abrir mapa/lista de entregas pendentes.
- Agrupar por bairro/raio/entregador.
- Mostrar SLA e atraso.
- Integrar com `entregadores` e futuras ofertas/auto-assign.

### Fase 5 - Historico e auditoria

- Historico com filtros por dia, status, canal, pagamento e cliente.
- Registro de quem alterou status, cancelou, recebeu ou reimprimiu.
- Exportacao simples para CSV/Excel no futuro.

## 7. Melhorias visuais especificas para MenuFlow

- Manter o estilo MenuFlow: `bg-bg-primary`, `bg-bg-secondary`, `border-border-light`, `primary-700`.
- Evitar painel de detalhe espremido no mobile; usar sheet.
- Status nunca apenas por cor: usar ponto/icone + texto.
- Cards de pedido devem ter hierarquia:
  1. numero + status;
  2. tempo/SLA;
  3. cliente/mesa/endereco;
  4. total/pagamento;
  5. itens resumidos.
- Atraso deve ser muito visivel, mas sem pintar o card inteiro.
- Estados obrigatorios:
  - carregando;
  - vazio com acao `Criar novo pedido`;
  - erro com `Tentar novamente`;
  - sucesso normal.

## 8. Diferencial que o MenuFlow deve superar

ClickEscale mostra pedidos e cria manualmente.

MenuFlow deve mostrar tambem:

- pedido pago vs pendente;
- margem/CMV quando houver ficha tecnica;
- origem do pedido: PDV, mesa, link, campanha, iFood, Rappi, 99Food;
- amarracao com `payment_paid` para marketing;
- status do caixa relacionado ao pedido;
- timeline auditavel do pedido.

## 9. Proxima fatia recomendada

Implementar Fase 1 da rota `/pedidos` usando o backend existente:

- `GET /orders?status=&from=&to=`
- `GET /orders/{id}`
- `PUT /orders/{id}/status`

Antes de mexer em backend, adicionar no frontend:

- rota;
- sidebar;
- page com lista + detalhe;
- chips de status;
- cards responsivos;
- empty/loading/error;
- painel de detalhe com acoes mockadas/condicionais.

## 10. Registro de execucao

- 2026-07-01: Fase 1 iniciada no frontend com rota `/pedidos`.
- 2026-07-01: Implementados filtros por periodo/status, busca local, lista de cards, detalhe lateral, avancar status e cancelar com motivo usando `/orders`.
- 2026-07-01: Navegacao adicionada na sidebar e titulo da topbar registrado.
- Limitacao conhecida: busca por nome/telefone do cliente ainda depende do backend expor esses campos em `OrderResponse`.
- 2026-07-01: Validacao tecnica passou com `npm run type-check`, `npm run lint` e `npm run build`; Next listou `/pedidos`.
- 2026-07-01: Validacao visual local em `http://127.0.0.1:3013/pedidos`: PC 1366, tablet 834 e mobile 390 sem overflow horizontal; evidencias salvas em `docs/outputs/pedidos-audit/`.

## 11. Atualizacao 2026-07-08 — estado real (via git, main @2e474c4)

> ⚠️ CORRECAO 2026-07-08 (2a passada): verificado no CODIGO (`frontend/app/pedidos/page.tsx`, 813 linhas, commit `15bff06`), nao no registro:

- **Fase 1 completa + PARTE da Fase 2 ja entregues** em `main`: consulta com filtros por periodo/status, busca local, lista de cards, detalhe lateral, **avancar status** (NEXT_STATUS + estado busy "Atualizando...") e **cancelar com motivo** (modal com razao obrigatoria) — tudo real via `/orders`.
- **Genuinamente pendente da Fase 2+:** acoes em LOTE, impressao, novo pedido manual (Fase 3), delivery/roteirizacao (Fase 4), historico/auditoria (Fase 5).
- Limitacao conhecida: busca por nome/telefone do cliente depende do backend expor esses campos em `OrderResponse` (ainda nao exposto).
