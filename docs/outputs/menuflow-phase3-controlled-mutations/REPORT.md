# MenuFlow Fase 3 - Mutacao Controlada

API: http://localhost:8088/api/v1
Data: 2026-06-30T10:57:22.095Z
Tenant: audit
Usuario: audit@menuflow.local
Prefixo: `AUDIT-20260630105721`

## Resultado

Etapas executadas: 14
Falhas: 0

- PASS: login-admin-audit
- PASS: dre-before-today
- PASS: create-audit-category
- PASS: create-audit-product-idempotent
- PASS: validate-public-menu-has-audit-product
- PASS: create-audit-coupon
- PASS: preview-audit-coupon-public
- PASS: create-audit-operating-expense
- PASS: create-public-order-with-coupon
- PASS: validate-coupon-redemption
- PASS: validate-kds-has-public-order
- PASS: create-pdv-order-idempotent
- PASS: pay-pdv-order-card
- PASS: dre-after-today

## Entidades AUDIT Criadas

- Categoria: `b34fed50-0e08-4b57-8e02-e81f73fce913`
- Produto: `86ed2629-62e9-4e34-923d-c46eea28a700`
- Cupom: `ec6f5dc5-48d3-4761-8623-04d7b3a22c94`
- Despesa: `2fb25fd5-2b27-4057-bffd-f745e57af8d3`
- Pedido publico: `764fa53a-cff7-49a1-bebe-4627b46db32e`
- Pedido PDV: `d4c3fca8-e865-4d2e-b85a-c2db4afac458`
- Pagamento PDV: `cb0238f0-48c8-4bb7-b711-1644da0d663d`

## Validacoes Realizadas

- Produto idempotente: mesma `Idempotency-Key` retornou o mesmo produto.
- Produto apareceu no cardapio publico do tenant `audit`.
- Cupom criado e preview publico retornou desconto esperado.
- Pedido publico aplicou cupom e registrou redemption.
- Pedido publico apareceu no KDS ativo.
- Pedido PDV idempotente retornou o mesmo pedido em replay.
- Pagamento CARD fechou pedido PDV sem depender de caixa aberto.
- DRE do dia refletiu despesa operacional e venda paga.

## Limites

- Esta fase cria dados reais no tenant de auditoria. Nao remove os registros para preservar evidencias.
- Pagamento testado com CARD; venda CASH exige fluxo separado de abertura/fechamento de caixa.