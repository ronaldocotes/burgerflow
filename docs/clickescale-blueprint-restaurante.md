# MenuFlow - Blueprint ClickEscale para restaurante

Data: 2026-06-26

Status: referencia de produto e arquitetura. Baseado em analise autenticada do painel ClickEscale de restaurante, sem credenciais, sem dados pessoais e sem acionar acoes de envio, publicacao, cobranca ou salvamento.

Relatorio completo da analise:

- `C:\Users\sdcot\Documents\Codex\2026-06-25\a\outputs\clickescale-analise-profunda-painel-restaurante-2026-06-26.md`

## 1. Decisao de produto

O MenuFlow deve ser tratado como **sistema operacional de restaurante com growth embutido**, nao como PDV isolado nem como ferramenta de trafego pago.

Funil canonico:

```text
cardapio
  -> link/QR
  -> campanha/WhatsApp/Instagram
  -> atendimento/IA
  -> carrinho/pedido
  -> pagamento confirmado
  -> caixa/carteira/financeiro
  -> cliente/fidelidade
  -> recompra/campanha
```

O diferencial de mercado deve ser: **pedido pago + margem + recompra**, nao clique, curtida ou post.

## 2. Modulos verificados no benchmark

ClickEscale demonstrou uma combinacao de modulos que deve orientar o MenuFlow:

- dashboard com visao geral, pedidos, clientes e marketing;
- gestao de pedidos;
- mesas e comandas;
- caixa;
- delivery por area;
- atendimento WhatsApp com IA;
- clientes, tags e fidelidade;
- cardapio, produtos e complementos;
- personalizacao do cardapio;
- cupons;
- central de estrategia;
- campanhas e criativos;
- laboratorio de campanhas por objetivo e mapa;
- disparos WhatsApp;
- estoque, movimentacoes, contagem e fichas tecnicas;
- financeiro, fluxo de caixa, lancamentos e acerto de entregadores;
- carteira Pix/saques;
- usuarios por papel operacional;
- plano/cobranca;
- integracoes;
- gestao de links/QR;
- copiloto IA do dono.

## 3. Decisoes canonicas para MenuFlow

### 3.1 Cardapio e produto

Produto precisa nascer com as dimensoes comerciais, operacionais e de growth:

- imagem;
- nome;
- categoria;
- descricao;
- preco base;
- preco promocional por dia/data;
- codigo de integracao/PDV;
- ficha tecnica;
- disponibilidade;
- canal de exibicao;
- complementos;
- destaque;
- visibilidade em links;
- estado ativo/inativo/indisponivel.

Entidades minimas:

```text
category
product
product_image
product_option_group
product_option
product_size
product_flavor
combo
combo_item
price_table
price_table_item
menu_channel_visibility
product_availability_window
technical_recipe
technical_recipe_item
```

Regras:

- dinheiro sempre em centavos;
- snapshot de preco, desconto, complemento e nome no item do pedido;
- produto arquivado/inativo nao entra em pedido novo;
- produto indisponivel pode aparecer no historico, mas nao no cardapio;
- alteracao de preco nao muda pedido antigo;
- pedido nao fecha sem complemento obrigatorio;
- baixa de estoque por ficha tecnica deve ser idempotente.

### 3.2 Links e QR

Links devem ser produto de primeira classe.

Tipos iniciais:

```text
DELIVERY
VIEW_ONLY
COUNTER
TABLE
CAMPAIGN
COUPON
PARTNER
INSTAGRAM
WHATSAPP
```

Todo link deve preservar:

- `tracking_slug`;
- `utm_source`;
- `utm_medium`;
- `utm_campaign`;
- `utm_content`;
- `utm_term`;
- `fbclid`;
- `gclid`;
- `gbraid`;
- `wbraid`;
- `ttclid`;
- cupom;
- contexto do QR.

### 3.3 Growth Center

Objetivos de campanha devem ser operacionais:

- vender pelo WhatsApp;
- vender pelo cardapio digital;
- reconhecimento/engajamento;
- vender produto parado;
- lotar horario fraco;
- reativar cliente inativo;
- girar estoque proximo do vencimento;
- aumentar ticket medio;
- vender item de maior margem.

Evento financeiro principal:

```text
payment_paid
```

`order_created` e sinal operacional, nao prova de ROAS.

### 3.4 IA

IA deve operar em dois papeis:

1. Atendimento do cliente.
2. Copiloto do dono.

Ferramentas minimas:

- consultar cardapio;
- consultar produto e complementos;
- montar carrinho;
- consultar horario e entrega;
- consultar status do pedido;
- acionar humano;
- consultar vendas;
- consultar margem/CMV;
- consultar estoque;
- consultar campanhas;
- sugerir combo/oferta;
- gerar rascunho de campanha.

Regras:

- IA nunca inventa preco, produto, prazo, estoque, taxa, pagamento, margem ou ROAS;
- se nao houver dados suficientes, deve dizer isso;
- publicar, gastar verba, disparar mensagem, alterar desconto ou mexer em pagamento exige aprovacao humana;
- recomendacao do dono deve virar rascunho/tarefa clicavel.

### 3.5 Financeiro

Financeiro deve nascer como razao de eventos, nao como soma solta.

Entidades-alvo:

```text
payment_intent
payment
wallet_transaction
payout
refund
fee
cash_session
cash_movement
financial_event
financial_ledger_entry
```

Regras:

- Pix aguardando pagamento nao entra no saldo;
- webhook/PSP e fonte de verdade para confirmar pagamento;
- saques e reembolsos tem status;
- DRE usa eventos financeiros reais;
- taxa, comissao, CMV e despesa precisam de trilha auditavel.

## 4. O que copiar e melhorar

Copiar:

- gestao de links com delivery, visualizacao e balcao;
- produto com abas de basico, preco, ficha tecnica, disponibilidade e complementos;
- campanha por objetivo de negocio;
- mapa para regiao da campanha/entrega;
- IA do dono analisando cardapio;
- atendimento IA com teste por persona;
- DRE automatica;
- carteira separando saldo, Pix pendente, saque e reembolso;
- integracoes como cards gerenciaveis;
- usuarios por papel operacional.

Melhorar:

- usar `payment_paid` em vez de `Purchase` generico;
- usar `event_id` para deduplicacao browser/server;
- exportar conversoes por outbox idempotente;
- usar WhatsApp oficial/opt-in/templates para produto comercial;
- nao vender "anti-ban";
- ligar campanhas a margem, estoque, clima, horario e recompra;
- transformar sugestoes da IA em tarefas com aprovacao.

## 5. Backlog implementavel

### Fase A - Fundacao operacional

- Cardapio completo: produto, categoria, complemento, canal, horario, ficha tecnica.
- Links/QR por contexto.
- Pedido, pagamento e customer automatico.
- Caixa e carteira como eventos financeiros.
- Auditoria e RBAC.

### Fase B - Growth first-party

- `tracking_link`;
- `marketing_session`;
- `marketing_event`;
- `marketing_campaign`;
- `marketing_conversion`;
- `marketing_export_outbox`;
- cupom por campanha;
- dashboard de `payment_paid`.

### Fase C - IA assistiva

- Copiloto do dono lendo cardapio, vendas, margem e estoque.
- Rascunho de campanha, combo, cupom e melhoria de cardapio.
- Evals de IA para:
  - sem dados suficientes;
  - produto sem ficha tecnica;
  - preco real;
  - carrinho abandonado;
  - loja fechada;
  - handoff humano.

### Fase D - Integracoes

- Meta Pixel + CAPI;
- TikTok Events API;
- Google Data Manager/offline conversions;
- iFood;
- Anota AI;
- Saipos;
- impressora termica;
- delivery sob demanda.

## 6. Definition of Done

- Dinheiro em centavos.
- Tenant isolado.
- RBAC deny-by-default.
- Auditoria em acao sensivel.
- `payment_paid` idempotente.
- Snapshot de pedido.
- Outbox para conversao externa.
- Consentimento/opt-in para WhatsApp e ads.
- Segredos cifrados.
- IA com ferramentas reais e aprovacao humana.
- Estados de tela: loading, vazio, erro e sucesso.
- Teste campanha -> link -> pedido -> pagamento -> conversao.
