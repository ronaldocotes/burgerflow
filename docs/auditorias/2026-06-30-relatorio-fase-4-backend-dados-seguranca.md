# Auditoria Fase 4 - Backend, Dados e Seguranca MenuFlow

Data: 2026-06-30
Ambiente: QA local (`backend :8088`, tenant `audit`)
Prefixo dos dados: `AUDIT4-20260630110704`

## Evidencias Geradas

- Relatorio bruto: `docs/outputs/menuflow-phase4-backend-security/REPORT.md`
- Dados estruturados: `docs/outputs/menuflow-phase4-backend-security/results.json`
- Executor: `scripts/run-phase4-backend-security-local.sh`
- Script API-only: `docs/auditorias/2026-06-30-menuflow-phase4-backend-security.cjs`

## Resultado

Foram executadas 15 etapas com 0 falhas.

## Entidades Criadas

- Categoria: `2abd8ab9-c346-449a-88f5-b2f3f44ed7ac`
- Produto: `bcb81947-6912-49c0-be68-efdd1dae89d7`

## Usuarios RBAC Garantidos

- Admin: `audit@menuflow.local`
- Operador: `audit.operator@menuflow.local`
- Staff: `audit.staff@menuflow.local`

Todos usam a senha de QA `Audit@1234` no tenant `audit`.

## Validacoes Confirmadas

- Login admin, operador e staff no tenant `audit`.
- Rota protegida `/products` retorna 401 sem token Bearer.
- OPERATOR acessa `/pdv/orders/active`.
- OPERATOR nao acessa `/dre`.
- STAFF nao cria produto.
- Produto criado com `Idempotency-Key`; replay com mesmo payload retornou o mesmo produto.
- Reuso da mesma `Idempotency-Key` com payload diferente retornou 409.
- `X-Tenant-ID: demo` spoofado nao alterou o tenant do JWT assinado.
- Cardapio publico nao expôs `costPriceCents`, `sku`, `createdAt` nem `updatedAt`.
- DRE com periodo invalido falhou fechado.
- Admin de restaurante nao conseguiu atribuir papel `SUPER_ADMIN`.
- Sistema bloqueou desativar o ultimo admin ativo.

## Leitura Tecnica

A rodada confirma os principais invariantes da Fase 4 inicial:

- Auth: sem Bearer, rota protegida fecha em 401.
- RBAC: papeis de operacao nao leem dados financeiros nem executam mutacao administrativa.
- Tenant: o escopo efetivo vem do token assinado, nao de header controlado pelo cliente.
- Idempotencia: retry legitimo re-serve; reuso indevido da chave retorna conflito.
- Contrato publico: cardapio publico segue enxuto, sem custo/margem/timestamps internos.
- Financeiro: DRE rejeita periodo inconsistente sem 500.
- Plataforma: `SUPER_ADMIN` nao e atribuivel por admin de restaurante.

## Limites

Esta fase cria uma categoria e um produto reais no tenant de auditoria para preservar evidencias. Nao executa exclusoes, nao altera usuarios de outros tenants e nao mede performance com `EXPLAIN`. Varredura de dependencias, headers de producao, logs e testes de performance devem entrar em uma rodada dedicada.
