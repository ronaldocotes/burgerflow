# MenuFlow Fase 4 - Backend, Dados e Seguranca

API: http://localhost:8088/api/v1
Data: 2026-06-30T11:07:05.121Z
Tenant: audit
Prefixo: `AUDIT4-20260630110704`

## Resultado

Etapas executadas: 15
Falhas: 0

- PASSED: login-admin-audit
- PASSED: login-operator-audit
- PASSED: login-staff-audit
- PASSED: protected-products-without-token-returns-401
- PASSED: operator-can-open-pdv-active-orders
- PASSED: operator-cannot-read-dre
- PASSED: staff-cannot-create-product
- PASSED: create-category-for-idempotency-check
- PASSED: product-idempotency-replay-same-payload
- PASSED: product-idempotency-rejects-different-payload
- PASSED: signed-token-tenant-binding-ignores-spoofed-header
- PASSED: public-menu-does-not-expose-admin-cost-fields
- PASSED: dre-invalid-period-fails-closed
- PASSED: admin-cannot-assign-super-admin-role
- PASSED: last-active-admin-cannot-be-disabled

## Entidades AUDIT Criadas

- Categoria: `2abd8ab9-c346-449a-88f5-b2f3f44ed7ac`
- Produto: `bcb81947-6912-49c0-be68-efdd1dae89d7`

## Validacoes Realizadas

- Rotas protegidas retornam 401 sem Bearer.
- OPERATOR acessa PDV ativo, mas nao acessa DRE.
- STAFF nao cria produto.
- Idempotency-Key re-serve mesma resposta para payload igual.
- Idempotency-Key com payload diferente retorna 409.
- Header X-Tenant-ID spoofado nao muda o tenant do JWT assinado.
- Cardapio publico nao expoe custo, SKU nem timestamps internos.
- DRE com periodo invalido falha fechado.
- Admin de restaurante nao atribui SUPER_ADMIN.
- Sistema bloqueia desativar o ultimo admin ativo.

## Limites

- Esta fase cria uma categoria e um produto reais no tenant de auditoria para preservar evidencias.
- Nao executa testes destrutivos de exclusao nem altera usuarios de outros tenants.
- Performance/EXPLAIN e varredura de dependencias ficam para uma rodada dedicada da Fase 4.