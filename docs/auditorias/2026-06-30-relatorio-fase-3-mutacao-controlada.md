# Auditoria Fase 3 - Mutacao Controlada MenuFlow

Data: 2026-06-30
Ambiente: QA local (`backend :8088`, tenant `audit`)
Prefixo dos dados: `AUDIT-20260630105721`

## Evidencias Geradas

- Relatorio bruto: `docs/outputs/menuflow-phase3-controlled-mutations/REPORT.md`
- Dados estruturados: `docs/outputs/menuflow-phase3-controlled-mutations/results.json`
- Executor: `scripts/run-phase3-controlled-mutations-local.sh`
- Script API-only: `docs/auditorias/2026-06-30-menuflow-phase3-controlled-mutations.cjs`

## Resultado

Foram executadas 14 etapas com 0 falhas.

## Entidades Criadas

- Categoria: `b34fed50-0e08-4b57-8e02-e81f73fce913`
- Produto: `86ed2629-62e9-4e34-923d-c46eea28a700`
- Cupom: `ec6f5dc5-48d3-4761-8623-04d7b3a22c94`
- Despesa: `2fb25fd5-2b27-4057-bffd-f745e57af8d3`
- Pedido publico: `764fa53a-cff7-49a1-bebe-4627b46db32e`
- Pedido PDV: `d4c3fca8-e865-4d2e-b85a-c2db4afac458`
- Pagamento PDV: `cb0238f0-48c8-4bb7-b711-1644da0d663d`

## Validacoes Confirmadas

- Login admin no tenant `audit`.
- DRE antes do teste estava zerado para o dia.
- Categoria `AUDIT-*` criada.
- Produto criado com `Idempotency-Key`; replay retornou o mesmo produto.
- Produto apareceu no cardapio publico.
- Cupom criado e preview publico retornou desconto de 500 centavos.
- Despesa operacional criada.
- Pedido publico aplicou cupom e registrou redemption.
- Pedido publico apareceu no KDS ativo.
- Pedido PDV criado com `Idempotency-Key`; replay retornou o mesmo pedido.
- Pagamento CARD fechou o pedido PDV sem depender de caixa aberto.
- DRE depois do teste refletiu venda paga e despesa operacional.

## DRE Verificado

Antes: receita bruta `0`, despesas operacionais `0`, pedidos `0`.

Depois: receita bruta `2590`, despesas operacionais `1234`, lucro liquido `1356`, pedidos `1`, pagamento `CREDIT_CARD`.

## Limites

A Fase 3 preserva as entidades criadas para auditoria. Venda em dinheiro nao foi testada aqui porque exige abertura/fechamento de caixa; esse fluxo deve entrar em uma rodada propria.
