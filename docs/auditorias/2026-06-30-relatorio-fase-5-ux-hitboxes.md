# Auditoria Fase 5.1 - Correcoes UX de Hitbox MenuFlow

Data: 2026-06-30
Ambiente: QA local (`frontend :3011`, `backend :8088`, tenant `audit`)
Persona: administrador critico usando PC, tablet e celular.

## Evidencias Geradas

- Relatorio bruto atualizado: `docs/outputs/menuflow-critical-audit/REPORT.md`
- Dados estruturados atualizados: `docs/outputs/menuflow-critical-audit/results.json`
- Screenshots atualizados: `docs/outputs/menuflow-critical-audit/*.png`
- Executor: `scripts/run-frontend-audit-local.sh`

## Resultado

A Fase 5.1 reduziu os achados brutos automatizados de 75 para 45, mantendo 60 screenshots.

## Correcoes Aplicadas

- KDS: botao `Atualizar` e acao `Cancelar` passaram a ter alvo clicavel minimo.
- Mesas: botao `Atualizar` e botao de QR Code passaram a ter alvo clicavel minimo.
- Caixa historico: botao de voltar passou de 36px para 44px.
- DRE: seletor de periodo e acoes de despesa usam alvo clicavel minimo.
- RFV, carrinhos, tracking, bot e conversoes: filtros/segmentos compactos ganharam `min-h-11`.
- Bot, fidelidade, configuracoes, carrinhos e usuarios: switches pequenos ganharam hitbox de 44px.
- Conversoes: botao de mostrar/ocultar token ganhou largura clicavel de 44px.
- Cupons: acoes icon-only passaram a usar `icon-button`.
- Cardapio admin: botoes de edicao pelo nome do produto ganharam alvo minimo.

## Validacoes

- `npm run type-check`: verde.
- `scripts/run-frontend-audit-local.sh`: verde.
- Auditoria final: 45 achados brutos, 60 screenshots.

## Residuos

Restam principalmente:

- `input` escondido/checkbox nativo pequeno em `admin/cardapio` e `admin/conversoes`, reportado pelo detector como alvo pequeno.
- Tabelas largas em `admin/cupons` e na lista de despesas do DRE mobile.
- Barras horizontais deliberadas de categorias em `/cardapio` e `/pdv` mobile.
- Textos de apoio e metadados com 12px em tablet/mobile, muitos vindos da topbar e de labels secundarias.

## Proxima Subfase Recomendada

Fase 5.2: transformar `admin/cupons` e a lista de despesas do DRE em cards ate `lg`, mantendo tabela apenas em desktop largo. Depois revisar texto pequeno da topbar/metadados com regra mais seletiva, para nao inflar tudo que e legenda secundaria.
