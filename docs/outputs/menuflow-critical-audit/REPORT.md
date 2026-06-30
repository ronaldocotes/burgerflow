# Auditoria critica MenuFlow

Base: http://localhost:3011
Data: 2026-06-30T16:26:32.090Z
Dispositivos: pc 1920x1080, tablet 768x1024, mobile 390x844

## Sumario executivo

Foram auditadas 18 areas em 3 viewports, com login, captura de screenshots, leitura de DOM, erros HTTP/console, overflow, alvos de toque e texto pequeno. Encontrados 2 achados automatizados. Esta rodada e uma auditoria operacional read-only: nao finalizou pedido nem gravou entidades para nao poluir a demo de producao.

## Achados por severidade

### Importante

- **Conteudo corta horizontalmente**
  - Dispositivo: mobile
  - Tela: /cardapio
  - O que acontece: scrollWidth=390, clientWidth=390, clipped=[{"tag":"BUTTON","role":null,"text":"Hamburgueres Artesanais","className":"min-h-11 flex-shrink-0 whitespace-nowrap rounded-full px-4 py-2 text-sm font-medium transition-colors duration-150 bg-bg-tertiary text-text-secondary hover:text-text-primary","ariaHidden":false,"left":344,"right":550,"top":344,"width":206,"height":44,"fontSize":14},{"tag":"BUTTON","role":null,"text":"Smash Burgers","className":"min-h-11 flex-shrink-0 whitespace-nowrap rounded-full px-4 py-2 text-sm font-medium transition-colors duration-150 bg-bg-tertiary text-text-secondary hover:text-text-primary","ariaHidden":false,"left":556,"right":690,"top":344,"width":134,"height":44,"fontSize":14},{"tag":"BUTTON","role":null,"text":"Frango e Vegetarianos","className":"min-h-11 flex-shrink-0 whitespace-nowrap rounded-full px-4 py-2 text-sm font-medium transition-colors duration-150 bg-bg-tertiary text-text-secondary hover:text-text-primary","ariaHidden":false,"left":696,"right":879,"top":344,"width":183,"height":44,"fontSize":14}]
  - O que deveria acontecer: Nao deve haver overflow horizontal em PC/tablet/mobile.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/public-cardapio-mobile.png
- **Conteudo corta horizontalmente**
  - Dispositivo: mobile
  - Tela: /pdv
  - O que acontece: scrollWidth=390, clientWidth=390, clipped=[{"tag":"BUTTON","role":null,"text":"Combos da Casa","className":"min-h-11 shrink-0 rounded-full px-4 py-2 text-sm font-medium transition-colors bg-bg-secondary text-text-secondary border border-border-light hover:bg-bg-tertiary","ariaHidden":false,"left":281,"right":429,"top":128,"width":148,"height":44,"fontSize":14},{"tag":"BUTTON","role":null,"text":"Hamburgueres Artesanais","className":"min-h-11 shrink-0 rounded-full px-4 py-2 text-sm font-medium transition-colors bg-bg-secondary text-text-secondary border border-border-light hover:bg-bg-tertiary","ariaHidden":false,"left":437,"right":645,"top":128,"width":208,"height":44,"fontSize":14},{"tag":"BUTTON","role":null,"text":"Smash Burgers","className":"min-h-11 shrink-0 rounded-full px-4 py-2 text-sm font-medium transition-colors bg-bg-secondary text-text-secondary border border-border-light hover:bg-bg-tertiary","ariaHidden":false,"left":653,"right":789,"top":128,"width":136,"height":44,"fontSize":14}]
  - O que deveria acontecer: Nao deve haver overflow horizontal em PC/tablet/mobile.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/pdv-mobile.png

## Top 5 prioridades

1. Importante: /cardapio (mobile) - Conteudo corta horizontalmente
2. Importante: /pdv (mobile) - Conteudo corta horizontalmente

## O que funcionou bem

- 54 combinacoes de rota/dispositivo renderizaram sem erro 500, sem erro Next e sem overflow horizontal detectado.
- O fluxo automatizado conseguiu autenticar como ADMIN e percorrer as telas autenticadas.
- Screenshots foram salvos para comparacao visual e regressao futura.

## Limites desta rodada

- Nao foram executadas mutacoes destrutivas ou de negocio, como finalizar pedido, criar cupom, editar usuario, resolver handoff ou salvar configuracoes.
- Validacoes de email, clipboard, WebSocket realtime entre abas, QR/impressao e Android double-back exigem rodada interativa especifica.
- Contraste WCAG foi inferido por heuristica visual/DOM; para fechar AA por cor exata, rodar auditoria CSS dedicada.