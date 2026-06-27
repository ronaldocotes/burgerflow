# MenuFlow - Alinhamento tecnico canonico

Data: 2026-06-24

Status: documento canonico de alinhamento entre codigo vivo, README antigo, plano Growth Center e caminho de producao.

## 1. Decisao tecnica

O caminho canonico atual do MenuFlow e:

```text
Backend Spring Boot/Kotlin
Frontend Next.js
Mobile React Native
IA FastAPI parcial
Postgres
Redis
Docker Compose
Caddy
Outbox transacional para eventos externos
```

Kafka e K3s nao sao a fundacao do MVP. Eles ficam adiados ate haver volume, equipe e necessidade operacional real.

## 2. O que esta implementado no codigo vivo

- Backend Spring Boot/Kotlin com entidades de pedido, item, produto, categoria, ingrediente, pagamento, idempotencia e delivery.
- Dinheiro em centavos no dominio de pedido e pagamento.
- Multi-tenant com banco de controle e bancos/tenants.
- KDS/WebSocket, PDV, Delivery, Auth, Refresh token, rate limit e testes backend.
- Docker Compose de producao com Caddy, Postgres, Redis e backend.
- Plano Growth Center salvo em `docs/growth-center-trafego-pago.md`.

## 3. O que esta parcial

- Frontend Next.js tem fundacao, dependencias e redirecionamento inicial, mas ainda precisa das telas operacionais completas.
- Mobile React Native tem estrutura e dependencias, mas ainda precisa dos fluxos finais de PDV/KDS/delivery/recebimento.
- IA FastAPI esta alinhada para rotas existentes de `health` e `demand_forecasting`; chatbot, WhatsApp, recommendations e analytics sao roadmap, nao codigo entregue.

## 4. Correcao aplicada na IA

Antes deste alinhamento, `ia/main.py` e `ia/app/api/v1/__init__.py` importavam routers ainda inexistentes:

- `chatbot`
- `recommendations`
- `whatsapp`
- `analytics`

Isso foi corrigido para importar somente:

- `health`
- `demand_forecasting`

Tambem foi adicionada a flag `KAFKA_ENABLED=false` por padrao. Com isso, a IA nao tenta inicializar broker Kafka no caminho atual de producao, que usa Docker Compose + Caddy + Postgres + Redis.

## 5. Infra canonica

### MVP / A1

- Caddy publica 80/443.
- Backend, Postgres e Redis ficam somente na rede interna.
- Segredos ficam em `.env.prod`, fora do git.
- Kafka, Zookeeper, PgAdmin e Kafka UI nao entram em producao MVP.
- Migracoes de tenant devem ser aplicadas de forma controlada antes do deploy.

### Futuro

Kafka ou outro broker so entra quando houver necessidade clara de throughput, integracao assincrona pesada ou multiplos consumidores reais.

K3s so entra quando a operacao justificar orquestracao de cluster. Para o porte atual, Docker Compose versionado e mais simples, barato e seguro.

## 6. Pagamentos e recebimentos

O modelo canonico futuro e:

```text
Order
  -> PaymentIntent
  -> ProviderAdapter
  -> WebhookEvent idempotente
  -> Payment confirmed
  -> Outbox
  -> KDS/Growth/relatorios
```

Meios como Pix, cartao online, cartao de maquininha, Asaas, InfinitePay/PagBank-style e Mercado Pago nao devem criar fluxos paralelos. Todos devem passar por contrato comum de intencao, confirmacao, conciliacao, estorno e auditoria.

## 7. Growth Center

Growth Center continua como modulo planejado, nao implementado.

Regra canonica:

- medir primeiro no banco proprio;
- usar `payment_paid` como conversao principal;
- preservar UTM/click ids/cupom ate pedido e pagamento;
- exportar conversoes por outbox;
- IA apenas como copiloto com aprovacao humana.

Atualizacao 2026-06-26 - benchmark ClickEscale:

- MenuFlow deve ser sistema operacional de restaurante com growth embutido.
- Cardapio, links/QR, pedido, pagamento, financeiro, estoque, cliente e IA devem compartilhar eventos e entidades.
- Novo documento canonico de produto: `docs/clickescale-blueprint-restaurante.md`.
- Growth so conta como diferencial quando fecha em `payment_paid`, margem e recompra.
- IA nunca inventa preco, produto, estoque, prazo, taxa, pagamento, margem ou ROAS; deve consultar ferramentas do dominio.

## 8. Documentos e fonte de verdade

Ordem de confianca:

1. Codigo vivo e testes.
2. `docker/docker-compose.prod.yml` e `docker/Caddyfile`.
3. Este documento.
4. `docs/growth-center-trafego-pago.md`.
5. README antigo, quando nao contradizer os itens acima.

## 9. Backlog de alinhamento restante

- Atualizar o README inteiro para refletir esta decisao canonica.
- Criar endpoints/telas reais para web e mobile conforme o backend ja suporta.
- Implementar PaymentIntent e provider adapters.
- Implementar modulo Growth com migrations e services.
- Criar testes da IA garantindo que somente routers existentes sao importados.
- Implementar links/QR por contexto: delivery, visualizacao, balcao, mesa, campanha, cupom, parceiro, Instagram e WhatsApp.
- Expandir catalogo para complementos, disponibilidade por canal/horario, ficha tecnica e snapshot de pedido.
