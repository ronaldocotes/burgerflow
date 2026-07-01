# Script/roteiro usado - inspecao ClickEscale dashboard

Data: 2026-07-01
Uso: registro reproduzivel do que foi observado para planejar o dashboard MenuFlow.

## Browser

Ambiente: in-app browser ja autenticado pelo usuario em `https://app.clickescale.com/dashboard`.

Trecho usado para extrair os textos da aba principal:

```js
const root = document.querySelector('main') || document.body;
const nodes = Array.from(root.querySelectorAll('h1,h2,h3,h4,p,span,button,a,div')).slice(0, 650);
const out = [];
for (const el of nodes) {
  const t = (el.innerText || '').replace(/\s+/g, ' ').trim();
  if (t && t.length < 140 && !out.includes(t)) out.push(t);
}
return out.join('\n');
```

Coordenadas dos tabs observadas no viewport da sessao:

```text
Visao Geral: x 350, y 116
Pedidos:     x 440, y 116
Clientes:    x 518, y 116
Marketing:   x 603, y 116
```

## Repo MenuFlow

Comandos usados no WSL:

```bash
cd /home/ronaldo/menuflow
find frontend/app -maxdepth 4 -type f -name page.tsx | sort
find docs -maxdepth 3 -type f | sort | sed -n '1,160p'
git status --short
sed -n '1,220p' frontend/app/page.tsx
sed -n '1,260p' frontend/components/layout/Sidebar.tsx
sed -n '1,240p' frontend/components/layout/Topbar.tsx
sed -n '1,260p' frontend/lib/api.ts
find backend/src/main/kotlin/com/menuflow -maxdepth 3 -type f | sort | sed -n '1,220p'
sed -n '1,240p' backend/src/main/kotlin/com/menuflow/controller/DreController.kt
sed -n '1,260p' backend/src/main/kotlin/com/menuflow/service/DreService.kt
sed -n '1,220p' backend/src/main/kotlin/com/menuflow/controller/TrackingController.kt
sed -n '1,220p' backend/src/main/kotlin/com/menuflow/controller/CampaignController.kt
sed -n '1,220p' backend/src/main/kotlin/com/menuflow/controller/CartSessionController.kt
sed -n '1,220p' backend/src/main/kotlin/com/menuflow/controller/RfvController.kt
sed -n '1,220p' backend/src/main/kotlin/com/menuflow/controller/CashSessionController.kt
sed -n '1,220p' docs/clickescale-blueprint-restaurante.md
sed -n '1,220p' docs/growth-center-trafego-pago.md
```

## Observacao de seguranca

Foram feitas apenas leituras e cliques em tabs do dashboard. Nenhum formulario foi enviado, campanha disparada, configuracao alterada ou dado externo transmitido.

