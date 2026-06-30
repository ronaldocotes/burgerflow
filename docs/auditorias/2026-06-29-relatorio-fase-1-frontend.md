# Auditoria Frontend Fase 1 - MenuFlow

Data: 2026-06-29
Ambiente: QA local (`frontend :3011`, `backend :8088`, tenant `audit`)
Persona: administrador critico usando PC, tablet e celular para operar o restaurante.

## Evidencias Geradas

- Relatorio bruto: `docs/outputs/menuflow-critical-audit/REPORT.md`
- Dados estruturados: `docs/outputs/menuflow-critical-audit/results.json`
- Screenshots: `docs/outputs/menuflow-critical-audit/*.png`
- Rotas: 18 areas x 3 viewports
- Total bruto automatizado: 103 achados

## Leitura Humana dos Achados

### Bloqueantes

Nenhum bloqueio total de tela foi confirmado na rodada read-only. Login, navegacao autenticada e renderizacao das rotas principais funcionaram.

### Importantes

1. **Cardapio admin desktop: botao "Criar" vira uma coluna gigante**
   - Evidencia: `admin-cardapio-pc.png`
   - Impacto: o formulario lateral fica visualmente desequilibrado e parece quebrado. Em PC, o admin perde confianca na tela mais importante do sistema.
   - Acao: reposicionar o rodape do formulario para uma linha normal, sem grid que estica o botao verticalmente.

2. **PDV e cardapio publico: chips de categoria cortam para fora da tela**
   - Evidencias: `pdv-pc.png`, `pdv-tablet.png`, `pdv-mobile.png`, `public-cardapio-tablet.png`
   - Impacto: categorias no fim da lista ficam parcialmente invisiveis. Em tablet e celular, isso parece bug de scroll horizontal.
   - Acao: envolver chips em container com `overflow-x-auto`, padding final e indicadores visuais corretos; evitar que o detector veja conteudo fora do viewport como corte incoerente.

3. **Cardapio publico: imagem externa quebrada retorna 404**
   - Evidencias: `public-cardapio-*.png`, HTTP 404 para uma URL Unsplash.
   - Impacto: cardapio publico pode mostrar produto sem imagem ou gerar erro de console; passa impressao de cardapio mal cuidado.
   - Acao: trocar a URL quebrada no seed oficial e/ou adicionar fallback visual robusto para erro de imagem.

4. **Alvos abaixo de 44px aparecem em varias telas**
   - Evidencia: `results.json`
   - Impacto: em PC e tablet pode ser aceitavel para menu lateral de escritorio, mas em mobile alguns botoes como hamburguer/acoes de tabela devem ter hitbox maior.
   - Acao: priorizar botoes de topo, acoes icon-only e switches mobile; nao tratar todos os 54 alertas como iguais.

5. **DRE mobile esta legivel, mas denso**
   - Evidencia: `dre-mobile.png`
   - Impacto: tela funciona, mas os blocos financeiros e linhas de demonstrativo exigem leitura cuidadosa; em restaurante real, o dono pode perder contexto rapido.
   - Acao: em fase posterior, criar agrupamento mais escaneavel e destacar margens/alertas quando houver movimento real.

## O Que Funcionou Bem

- Login admin no tenant `audit` funcionou.
- Cardapio admin melhorou bastante frente ao print inicial: mobile usa cards, tablet nao mostra formulario lateral apertado, e desktop tem split view produtivo.
- Usuarios mobile esta claro e profissional.
- DRE desktop tem hierarquia boa e leitura financeira organizada.
- PDV mobile esta simples e rapido para selecionar produto.

## Correcao Imediata Escolhida

Corrigir nesta rodada:

1. Botao gigante do formulario lateral em `admin/cardapio`.
2. Corte visual dos chips de categoria no PDV/cardapio publico.
3. URL quebrada de imagem no seed oficial.
4. Tabela de horario do Bot WhatsApp no mobile.

## Resultado Pos-Correcao

- Auditoria final executada com sucesso por `scripts/run-frontend-audit-local.sh`.
- Achados brutos automatizados: caiu de 103 para 93.
- HTTP 404 de imagem: caiu de 3 para 0.
- Overflows brutos: caiu de 7 para 3.
- `admin/cardapio` desktop: botao `Criar` voltou a altura normal.
- `pdv` tablet/PC: chips de categoria quebram linha e nao somem para fora da tela.
- `admin/bot` mobile: horarios agora usam cards mobile em vez de tabela larga.

Restam 3 overflows brutos:

- `mobile /cardapio`: rolagem horizontal deliberada da barra de categorias.
- `mobile /pdv`: rolagem horizontal deliberada da barra de categorias.
- `tablet /admin/usuarios`: tabela passa poucos pixels do viewport por causa de margem/padding; corrigido na rodada seguinte trocando tablet para cards e mantendo tabela apenas em `lg+`.

Deixar para fase posterior:

- Revisao fina de 44px em todos os menus.
- Melhorias de densidade do DRE mobile.
- Auditoria de contraste CSS dedicada.

## Rodada Seguinte - Responsividade Administrativa

- `admin/usuarios`: tablet agora usa cards, evitando a tabela espremida em 768px; tabela fica restrita a desktop largo.
- Topbar: botao hamburguer, menu do usuario e item "Sair" passaram a ter hitbox minima de 44px.
- Sidebar: links, fechar menu mobile e recolher/expandir ganharam hitbox minima de 44px.
- Design system: `btn-primary`, `btn-secondary`, `btn-outline`, `input-field`, `input` e `icon-button` agora nascem com hitbox minima de 44px.
- Cardapio publico/PDV: chips de categoria e botao voltar do cardapio publico ganharam altura minima de toque.
- Revalidacao final: `scripts/run-frontend-audit-local.sh` executado com 60 screenshots e 75 achados brutos. Decomposicao: 37 alvos de toque, 36 textos abaixo de 14px, 2 overflows deliberados em barras horizontais mobile (`/cardapio` e `/pdv`).
