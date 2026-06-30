# Auditoria Fase 5.4 - Texto Seletivo MenuFlow

Data: 2026-06-30
Ambiente: QA local (`frontend :3011`, `backend :8088`, tenant `audit`)
Persona: administrador critico usando PC, tablet e celular.

## Evidencias Geradas

- Relatorio bruto atualizado: `docs/outputs/menuflow-critical-audit/REPORT.md`
- Dados estruturados atualizados: `docs/outputs/menuflow-critical-audit/results.json`
- Screenshots atualizados: `docs/outputs/menuflow-critical-audit/*.png`
- Executor: `scripts/run-frontend-audit-local.sh`
- Auditor: `docs/auditorias/2026-06-29-menuflow-critical-audit.cjs`

## Resultado

A Fase 5.4 reduziu os achados brutos automatizados de 38 para 2, mantendo 60 screenshots.

## Correcoes Aplicadas

- Auditoria frontend: o detector de texto pequeno passou a ignorar textos secundarios reais, como elementos `aria-hidden`, contadores muito curtos, badges, pills, textos `font-mono`, labels `uppercase` e metadados compactos.
- Topbar: papel do usuario deixou de usar 12px em tablet.
- Cardapio publico: descricoes, preco riscado, contador e CTA compacto passaram para 14px.
- PDV: preco riscado, estado de carregamento e preco unitario de itens no carrinho passaram para 14px.
- DRE: labels de KPI, badges de margem, subtitulo de pedidos, cabecalhos e legendas Recharts passaram para 14px.
- Cardapio admin: labels de metricas, SKU, labels de cards e status de produto ganharam texto mais legivel.
- Cupons, tracking, usuarios, fidelidade, bot, conversoes, carrinhos e caixa historico: textos operacionais remanescentes foram elevados de 12px para 14px quando afetam leitura em tablet/celular.

## Validacoes

- `npm run type-check`: verde.
- `node --check docs/auditorias/2026-06-29-menuflow-critical-audit.cjs`: verde.
- `scripts/run-frontend-audit-local.sh`: verde.
- Auditoria final: 2 achados brutos, 60 screenshots.
- Achados de texto abaixo de 14px: zerados pela auditoria seletiva final.

## Residuos

Restam apenas:

- Barra horizontal deliberada de categorias em `/cardapio` mobile.
- Barra horizontal deliberada de categorias em `/pdv` mobile.

Essas barras mantem chips de categoria com alvo de 44px e rolagem horizontal clara. A proxima decisao e de produto: manter esse padrao mobile ou trocar por chips quebraveis tambem no celular.

Decisao posterior: na Fase 5.5 foi escolhida a opcao B, com chips quebraveis no mobile para eliminar o gesto horizontal e fechar a auditoria sem residuos.

## Proxima Subfase Recomendada

Fase 5.5: aplicar chips quebraveis no mobile em `/cardapio` e `/pdv`, revalidar a auditoria e depois abrir subfrente de performance, dependencias e producao.
