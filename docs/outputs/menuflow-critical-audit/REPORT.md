# Auditoria critica MenuFlow

Base: http://localhost:3011
Data: 2026-06-30T12:40:14.748Z
Dispositivos: pc 1920x1080, tablet 768x1024, mobile 390x844

## Sumario executivo

Foram auditadas 18 areas em 3 viewports, com login, captura de screenshots, leitura de DOM, erros HTTP/console, overflow, alvos de toque e texto pequeno. Encontrados 38 achados automatizados. Esta rodada e uma auditoria operacional read-only: nao finalizou pedido nem gravou entidades para nao poluir a demo de producao.

## Achados por severidade

### Importante

- **Conteudo corta horizontalmente**
  - Dispositivo: mobile
  - Tela: /cardapio
  - O que acontece: scrollWidth=390, clientWidth=390, clipped=[{"tag":"BUTTON","role":null,"text":"Hamburgueres Artesanais","left":344,"right":550,"top":344,"width":206,"height":44,"fontSize":14},{"tag":"BUTTON","role":null,"text":"Smash Burgers","left":556,"right":690,"top":344,"width":134,"height":44,"fontSize":14},{"tag":"BUTTON","role":null,"text":"Frango e Vegetarianos","left":696,"right":879,"top":344,"width":183,"height":44,"fontSize":14}]
  - O que deveria acontecer: Nao deve haver overflow horizontal em PC/tablet/mobile.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/public-cardapio-mobile.png
- **Conteudo corta horizontalmente**
  - Dispositivo: mobile
  - Tela: /pdv
  - O que acontece: scrollWidth=390, clientWidth=390, clipped=[{"tag":"BUTTON","role":null,"text":"Combos da Casa","left":281,"right":429,"top":128,"width":148,"height":44,"fontSize":14},{"tag":"BUTTON","role":null,"text":"Hamburgueres Artesanais","left":437,"right":645,"top":128,"width":208,"height":44,"fontSize":14},{"tag":"BUTTON","role":null,"text":"Smash Burgers","left":653,"right":789,"top":128,"width":136,"height":44,"fontSize":14}]
  - O que deveria acontecer: Nao deve haver overflow horizontal em PC/tablet/mobile.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/pdv-mobile.png

### Melhoria

- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /cardapio
  - O que acontece: [{"tag":"P","role":null,"text":"Salsicha artesanal defumada, cebola crispy, queijo derretido, mostarda dijon e ketchup art","left":36,"right":241,"top":846,"width":205,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"DESTAQUE","left":32,"right":115,"top":1019,"width":83,"height":22,"fontSize":12},{"tag":"P","role":null,"text":"Inteira ou meia a meia. Escolha tamanho, sabor(es) e borda.","left":36,"right":241,"top":1237,"width":205,"height":32,"fontSize":12},{"tag":"P","role":null,"text":"Ingredientes premium. Meia a meia disponivel. Bordas exclusivas.","left":281,"right":487,"top":1237,"width":205,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"PROMO","left":32,"right":95,"top":1410,"width":63,"height":22,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/public-cardapio-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /pdv
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"R$ 36,90","left":343,"right":394,"top":403,"width":52,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"R$ 49,90","left":570,"right":622,"top":403,"width":52,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"R$ 27,90","left":115,"right":165,"top":991,"width":50,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/pdv-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /kds
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"0","left":83,"right":107,"top":153,"width":24,"height":20,"fontSize":12},{"tag":"SPAN","role":null,"text":"1","left":350,"right":371,"top":153,"width":21,"height":20,"fontSize":12},{"tag":"DIV","role":null,"text":"Mesa· Mesa AUDIT","left":276,"right":364,"top":305,"width":88,"height":32,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/kds-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /mesas
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/mesas-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /caixa
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/caixa-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /caixa/historico
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12},{"tag":"DIV","role":null,"text":"AberturaFechamentoFundoEsperadoContadoDiferenca","left":25,"right":687,"top":149,"width":662,"height":41,"fontSize":12},{"tag":"SPAN","role":null,"text":"Abertura","left":41,"right":194,"top":161,"width":153,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"Fechamento","left":210,"right":362,"top":161,"width":153,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/caixa-historico-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /admin/cardapio
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12},{"tag":"P","role":null,"text":"Produtos ativos","left":32,"right":161,"top":236,"width":129,"height":16,"fontSize":12},{"tag":"P","role":null,"text":"Categorias","left":205,"right":334,"top":236,"width":129,"height":16,"fontSize":12},{"tag":"P","role":null,"text":"Insumos","left":378,"right":507,"top":236,"width":129,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-cardapio-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /admin/usuarios
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"(voce)","left":162,"right":198,"top":237,"width":36,"height":15,"fontSize":12},{"tag":"P","role":null,"text":"audit@menuflow.local","left":49,"right":198,"top":254,"width":149,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":561,"right":663,"top":238,"width":102,"height":20,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-usuarios-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /financeiro/dre
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12},{"tag":"P","role":null,"text":"Receita Bruta","left":36,"right":154,"top":256,"width":118,"height":16,"fontSize":12},{"tag":"P","role":null,"text":"Lucro Bruto","left":210,"right":328,"top":256,"width":118,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"100.0%","left":218,"right":294,"top":318,"width":76,"height":20,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/dre-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /admin/cupons
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"Ativo","left":605,"right":671,"top":165,"width":66,"height":20,"fontSize":12},{"tag":"DT","role":null,"text":"Tipo","left":41,"right":350,"top":229,"width":309,"height":16,"fontSize":12},{"tag":"DT","role":null,"text":"Desconto","left":362,"right":671,"top":229,"width":309,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-cupons-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /admin/fidelidade
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12},{"tag":"P","role":null,"text":"Clientes acumulam pontos a cada pedido pago","left":68,"right":334,"top":246,"width":266,"height":16,"fontSize":12},{"tag":"P","role":null,"text":"Cole o ID do cliente (UUID) para consultar o saldo e historico de fidelidade.","left":68,"right":644,"top":676,"width":576,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-fidelidade-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /admin/rfv
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-rfv-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /admin/campanhas
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-campanhas-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /admin/carrinhos
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"{nome}","left":36,"right":97,"top":1048,"width":61,"height":22,"fontSize":12},{"tag":"SPAN","role":null,"text":"{total}","left":103,"right":172,"top":1048,"width":69,"height":22,"fontSize":12},{"tag":"SPAN","role":null,"text":"{link}","left":178,"right":239,"top":1048,"width":61,"height":22,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-carrinhos-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /admin/tracking
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12},{"tag":"BUTTON","role":null,"text":"7 dias","left":369,"right":428,"top":152,"width":60,"height":44,"fontSize":12},{"tag":"BUTTON","role":null,"text":"30 dias","left":436,"right":504,"top":152,"width":68,"height":44,"fontSize":12},{"tag":"BUTTON","role":null,"text":"90 dias","left":512,"right":581,"top":152,"width":68,"height":44,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-tracking-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /admin/conversoes
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12},{"tag":"LABEL","role":null,"text":"Pixel ID","left":48,"right":348,"top":313,"width":300,"height":16,"fontSize":12},{"tag":"LABEL","role":null,"text":"Access Token (write-only)","left":364,"right":664,"top":313,"width":300,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"(write-only)","left":448,"right":515,"top":313,"width":68,"height":15,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-conversoes-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /admin/bot
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12},{"tag":"P","role":null,"text":"Cliente digita esta palavra para falar com um humano","left":36,"right":676,"top":704,"width":640,"height":16,"fontSize":12},{"tag":"P","role":null,"text":"Enviada automaticamente quando o cliente solicita atendimento humano","left":36,"right":676,"top":1012,"width":640,"height":16,"fontSize":12},{"tag":"TH","role":null,"text":"Dia","left":36,"right":151,"top":1220,"width":115,"height":33,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-bot-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: tablet
  - Tela: /configuracoes
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":543,"right":575,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Administrador","left":583,"right":664,"top":20,"width":81,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/configuracoes-tablet.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /cardapio
  - O que acontece: [{"tag":"P","role":null,"text":"Salsicha artesanal defumada, cebola crispy, queijo derretido, mostarda dijon e ketchup art","left":28,"right":175,"top":688,"width":147,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"DESTAQUE","left":24,"right":107,"top":861,"width":83,"height":22,"fontSize":12},{"tag":"P","role":null,"text":"Inteira ou meia a meia. Escolha tamanho, sabor(es) e borda.","left":28,"right":175,"top":1079,"width":147,"height":32,"fontSize":12},{"tag":"P","role":null,"text":"Ingredientes premium. Meia a meia disponivel. Bordas exclusivas.","left":215,"right":362,"top":1079,"width":147,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"PROMO","left":24,"right":87,"top":1252,"width":63,"height":22,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/public-cardapio-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /pdv
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"R$ 36,90","left":111,"right":163,"top":341,"width":52,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"R$ 49,90","left":112,"right":164,"top":439,"width":52,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"R$ 27,90","left":111,"right":161,"top":2007,"width":50,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"R$ 27,90","left":108,"right":158,"top":4065,"width":50,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/pdv-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /kds
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"0","left":83,"right":107,"top":153,"width":24,"height":20,"fontSize":12},{"tag":"SPAN","role":null,"text":"1","left":199,"right":220,"top":163,"width":21,"height":20,"fontSize":12},{"tag":"DIV","role":null,"text":"Mesa· Mesa AUDIT","left":150,"right":238,"top":325,"width":88,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Mesa","left":150,"right":181,"top":333,"width":31,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/kds-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /mesas
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/mesas-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /caixa
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/caixa-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /caixa/historico
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/caixa-historico-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /admin/cardapio
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"P","role":null,"text":"Produtos ativos","left":32,"right":145,"top":344,"width":113,"height":16,"fontSize":12},{"tag":"P","role":null,"text":"Categorias","left":189,"right":302,"top":344,"width":113,"height":16,"fontSize":12},{"tag":"P","role":null,"text":"Insumos","left":32,"right":145,"top":468,"width":113,"height":16,"fontSize":12},{"tag":"P","role":null,"text":"Com imagem","left":189,"right":302,"top":468,"width":113,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-cardapio-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /admin/usuarios
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"P","role":null,"text":"audit.operator@menuflow.local","left":49,"right":226,"top":330,"width":177,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"OPERATOR","left":238,"right":322,"top":314,"width":84,"height":20,"fontSize":12},{"tag":"DT","role":null,"text":"Status","left":49,"right":96,"top":365,"width":47,"height":16,"fontSize":12},{"tag":"DT","role":null,"text":"Ultimo acesso","left":49,"right":147,"top":398,"width":98,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-usuarios-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /financeiro/dre
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"P","role":null,"text":"Receita Bruta","left":36,"right":139,"top":340,"width":103,"height":16,"fontSize":12},{"tag":"P","role":null,"text":"Lucro Bruto","left":195,"right":298,"top":340,"width":103,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"100.0%","left":203,"right":279,"top":402,"width":76,"height":20,"fontSize":12},{"tag":"P","role":null,"text":"Lucro Liquido","left":36,"right":139,"top":480,"width":103,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/dre-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /admin/cupons
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"Ativo","left":227,"right":293,"top":185,"width":66,"height":20,"fontSize":12},{"tag":"DT","role":null,"text":"Tipo","left":41,"right":161,"top":313,"width":120,"height":16,"fontSize":12},{"tag":"DT","role":null,"text":"Desconto","left":173,"right":293,"top":313,"width":120,"height":16,"fontSize":12},{"tag":"DT","role":null,"text":"Validade","left":41,"right":161,"top":365,"width":120,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-cupons-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /admin/fidelidade
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"P","role":null,"text":"Clientes acumulam pontos a cada pedido pago","left":48,"right":222,"top":264,"width":174,"height":32,"fontSize":12},{"tag":"P","role":null,"text":"Cole o ID do cliente (UUID) para consultar o saldo e historico de fidelidade.","left":48,"right":286,"top":708,"width":238,"height":32,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-fidelidade-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /admin/rfv
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-rfv-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /admin/campanhas
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-campanhas-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /admin/carrinhos
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"SPAN","role":null,"text":"{nome}","left":36,"right":97,"top":1276,"width":61,"height":22,"fontSize":12},{"tag":"SPAN","role":null,"text":"{total}","left":103,"right":172,"top":1276,"width":69,"height":22,"fontSize":12},{"tag":"SPAN","role":null,"text":"{link}","left":178,"right":239,"top":1276,"width":61,"height":22,"fontSize":12},{"tag":"P","role":null,"text":"Variaveis: {nome}, {total}, {link}","left":36,"right":298,"top":1304,"width":262,"height":16,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-carrinhos-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /admin/tracking
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"BUTTON","role":null,"text":"7 dias","left":16,"right":76,"top":188,"width":60,"height":44,"fontSize":12},{"tag":"BUTTON","role":null,"text":"30 dias","left":84,"right":152,"top":188,"width":68,"height":44,"fontSize":12},{"tag":"BUTTON","role":null,"text":"90 dias","left":160,"right":228,"top":188,"width":68,"height":44,"fontSize":12},{"tag":"BUTTON","role":null,"text":"Personalizado","left":16,"right":123,"top":240,"width":107,"height":44,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-tracking-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /admin/conversoes
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"LABEL","role":null,"text":"Pixel ID","left":48,"right":286,"top":369,"width":238,"height":16,"fontSize":12},{"tag":"LABEL","role":null,"text":"Access Token (write-only)","left":48,"right":286,"top":451,"width":238,"height":16,"fontSize":12},{"tag":"SPAN","role":null,"text":"(write-only)","left":132,"right":199,"top":451,"width":68,"height":15,"fontSize":12},{"tag":"SPAN","role":null,"text":"Nao configurado","left":48,"right":138,"top":525,"width":90,"height":36,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-conversoes-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /admin/bot
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12},{"tag":"P","role":null,"text":"Cliente digita esta palavra para falar com um humano","left":36,"right":298,"top":792,"width":262,"height":32,"fontSize":12},{"tag":"P","role":null,"text":"Enviada automaticamente quando o cliente solicita atendimento humano","left":36,"right":298,"top":1116,"width":262,"height":32,"fontSize":12},{"tag":"LABEL","role":null,"text":"Abertura","left":49,"right":163,"top":1429,"width":114,"height":64,"fontSize":12},{"tag":"LABEL","role":null,"text":"Fechamento","left":171,"right":285,"top":1429,"width":114,"height":64,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/admin-bot-mobile.png
- **Texto abaixo de 14px em tablet/mobile**
  - Dispositivo: mobile
  - Tela: /configuracoes
  - O que acontece: [{"tag":"DIV","role":null,"text":"5","left":254,"right":286,"top":12,"width":32,"height":32,"fontSize":12}]
  - O que deveria acontecer: Texto operacional deve manter legibilidade minima de 14px.
  - Evidencia: /home/ronaldo/menuflow/docs/outputs/menuflow-critical-audit/configuracoes-mobile.png

## Top 5 prioridades

1. Melhoria: /cardapio (tablet) - Texto abaixo de 14px em tablet/mobile
2. Melhoria: /pdv (tablet) - Texto abaixo de 14px em tablet/mobile
3. Melhoria: /kds (tablet) - Texto abaixo de 14px em tablet/mobile
4. Melhoria: /mesas (tablet) - Texto abaixo de 14px em tablet/mobile
5. Melhoria: /caixa (tablet) - Texto abaixo de 14px em tablet/mobile

## O que funcionou bem

- 54 combinacoes de rota/dispositivo renderizaram sem erro 500, sem erro Next e sem overflow horizontal detectado.
- O fluxo automatizado conseguiu autenticar como ADMIN e percorrer as telas autenticadas.
- Screenshots foram salvos para comparacao visual e regressao futura.

## Limites desta rodada

- Nao foram executadas mutacoes destrutivas ou de negocio, como finalizar pedido, criar cupom, editar usuario, resolver handoff ou salvar configuracoes.
- Validacoes de email, clipboard, WebSocket realtime entre abas, QR/impressao e Android double-back exigem rodada interativa especifica.
- Contraste WCAG foi inferido por heuristica visual/DOM; para fechar AA por cor exata, rodar auditoria CSS dedicada.