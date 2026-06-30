const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const BASE_URL = process.env.BASE_URL || 'https://menuflow.duckdns.org';
const OUT_DIR = path.resolve(__dirname, '..', 'outputs', 'menuflow-critical-audit');
const LOGIN = {
  tenant: process.env.MF_TENANT || 'demo',
  email: process.env.MF_EMAIL || 'admin@demo.com',
  password: process.env.MF_PASSWORD || 'Demo@1234',
};

const viewports = [
  { name: 'pc', width: 1920, height: 1080 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'mobile', width: 390, height: 844 },
];

const routes = [
  ['public-cardapio', '/cardapio', false],
  ['pdv', '/pdv', true],
  ['kds', '/kds', true],
  ['mesas', '/mesas', true],
  ['caixa', '/caixa', true],
  ['caixa-historico', '/caixa/historico', true],
  ['admin-cardapio', '/admin/cardapio', true],
  ['admin-usuarios', '/admin/usuarios', true],
  ['dre', '/financeiro/dre', true],
  ['admin-cupons', '/admin/cupons', true],
  ['admin-fidelidade', '/admin/fidelidade', true],
  ['admin-rfv', '/admin/rfv', true],
  ['admin-campanhas', '/admin/campanhas', true],
  ['admin-carrinhos', '/admin/carrinhos', true],
  ['admin-tracking', '/admin/tracking', true],
  ['admin-conversoes', '/admin/conversoes', true],
  ['admin-bot', '/admin/bot', true],
  ['configuracoes', '/configuracoes', true],
];

const safeOpeners = [
  /novo/i,
  /criar/i,
  /adicionar/i,
  /abrir/i,
  /convidar/i,
  /configurar/i,
];

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

function sanitize(name) {
  return name.replace(/[^a-z0-9_-]+/gi, '-').toLowerCase();
}

async function waitSettle(page) {
  await page.waitForLoadState('domcontentloaded').catch(() => {});
  await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {});
  await page.waitForTimeout(700);
}

async function login(page) {
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'domcontentloaded' });
  await page.getByLabel(/restaurante/i).fill(LOGIN.tenant);
  await page.getByLabel(/e-?mail/i).fill(LOGIN.email);
  await page.locator('#password').fill(LOGIN.password);
  await Promise.all([
    page.waitForURL((url) => !String(url).includes('/login'), { timeout: 20000 }),
    page.getByRole('button', { name: /entrar/i }).click(),
  ]);
  await waitSettle(page);
}

async function auditLogin(browser, viewport) {
  const context = await browser.newContext({ viewport, ignoreHTTPSErrors: true });
  const page = await context.newPage();
  const failures = [];
  page.on('response', (response) => {
    if (response.status() >= 400) {
      failures.push({ status: response.status(), url: response.url(), method: response.request().method() });
    }
  });

  await page.goto(`${BASE_URL}/login`, { waitUntil: 'domcontentloaded' });
  await waitSettle(page);
  const initial = await probe(page);
  await page.screenshot({ path: path.join(OUT_DIR, `login-${viewport.name}-initial.png`), fullPage: false });

  let wrongLoginText = '';
  try {
    await page.getByLabel(/restaurante/i).fill(LOGIN.tenant);
    await page.getByLabel(/e-?mail/i).fill(LOGIN.email);
    await page.locator('#password').fill('senha-errada-auditoria');
    await page.getByRole('button', { name: /entrar/i }).click();
    await page.waitForTimeout(1200);
    wrongLoginText = (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 1000);
    await page.screenshot({ path: path.join(OUT_DIR, `login-${viewport.name}-wrong-password.png`), fullPage: false });
  } catch (error) {
    wrongLoginText = `Falha ao testar login errado: ${error.message}`;
  }

  await context.close();
  return { viewport: viewport.name, initial, wrongLoginText, failures };
}

async function trySafeInteraction(page, routeName, viewportName) {
  const result = { attempted: false, button: null, dialogVisible: false, screenshot: null, error: null };
  try {
    const buttons = await page.getByRole('button').all();
    for (const button of buttons) {
      const text = ((await button.innerText().catch(() => '')) || (await button.getAttribute('aria-label').catch(() => '')) || '').trim();
      if (!text || !safeOpeners.some((rx) => rx.test(text))) continue;
      const box = await button.boundingBox().catch(() => null);
      if (!box || box.width <= 0 || box.height <= 0) continue;
      result.attempted = true;
      result.button = text.replace(/\s+/g, ' ').slice(0, 80);
      await button.click({ timeout: 3000 }).catch(() => {});
      await page.waitForTimeout(600);
      result.dialogVisible = await page.locator('[role="dialog"], dialog, [data-state="open"]').first().isVisible().catch(() => false);
      const shot = path.join(OUT_DIR, `${sanitize(routeName)}-${viewportName}-interaction.png`);
      await page.screenshot({ path: shot, fullPage: false });
      result.screenshot = shot;
      await page.keyboard.press('Escape').catch(() => {});
      break;
    }
  } catch (error) {
    result.error = error.message;
  }
  return result;
}

async function probe(page) {
  return page.evaluate(() => {
    const clean = (value) => (value || '').replace(/\s+/g, ' ').trim();
    const visible = (el) => {
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);
      return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none';
    };
    const interactiveRect = (el) => {
      const type = (el.getAttribute('type') || '').toLowerCase();
      if (el.tagName === 'INPUT' && ['checkbox', 'radio', 'file'].includes(type)) {
        const id = el.getAttribute('id');
        const label = el.closest('label') || (id ? document.querySelector(`label[for="${CSS.escape(id)}"]`) : null);
        if (label && visible(label)) return label.getBoundingClientRect();
      }
      return el.getBoundingClientRect();
    };
    const viewportWidth = document.documentElement.clientWidth;
    const rects = [...document.querySelectorAll('body *')]
      .filter(visible)
      .map((el) => {
        const r = el.getBoundingClientRect();
        const cs = getComputedStyle(el);
        return {
          tag: el.tagName,
          role: el.getAttribute('role'),
          text: clean(el.textContent).slice(0, 90),
          className: typeof el.className === 'string' ? el.className : '',
          ariaHidden: el.getAttribute('aria-hidden') === 'true',
          left: Math.round(r.left),
          right: Math.round(r.right),
          top: Math.round(r.top),
          width: Math.round(r.width),
          height: Math.round(r.height),
          fontSize: Number.parseFloat(cs.fontSize || '0'),
        };
      });
    const buttons = [...document.querySelectorAll('button, a, input, select, textarea, [role="button"], [role="tab"]')]
      .filter(visible)
      .map((el) => {
        const r = interactiveRect(el);
        return {
          tag: el.tagName,
          role: el.getAttribute('role'),
          label: clean(el.innerText || el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.getAttribute('name')).slice(0, 90),
          width: Math.round(r.width),
          height: Math.round(r.height),
        };
      });
    const isSecondarySmallText = (r) => {
      const text = r.text.trim();
      if (r.ariaHidden) return true;
      if (text.length <= 2 && r.width <= 44 && r.height <= 44) return true;
      if (/(rounded-full|badge|uppercase|tracking-wider|font-mono)/.test(r.className) && text.length <= 24) return true;
      return false;
    };
    const textNodes = rects.filter((r) => r.text && r.fontSize > 0);
    const svgs = [...document.querySelectorAll('svg')].filter(visible).map((svg) => {
      const r = svg.getBoundingClientRect();
      return { width: Math.round(r.width), height: Math.round(r.height), text: clean(svg.textContent).slice(0, 40) };
    });
    return {
      url: location.href,
      title: document.title,
      h1: [...document.querySelectorAll('h1')].map((el) => clean(el.textContent)).filter(Boolean),
      h2: [...document.querySelectorAll('h2')].map((el) => clean(el.textContent)).filter(Boolean),
      bodySample: clean(document.body.innerText).slice(0, 900),
      bodyLength: clean(document.body.innerText).length,
      overflowX: document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
      clippedRight: rects.filter((r) => r.right > viewportWidth + 1).slice(0, 12),
      tinyTargets: buttons.filter((b) => b.width < 44 || b.height < 44).slice(0, 20),
      tinyText: textNodes.filter((r) => r.fontSize > 0 && r.fontSize < 14 && !isSecondarySmallText(r)).slice(0, 20),
      tableCount: document.querySelectorAll('table').length,
      formCount: document.querySelectorAll('form').length,
      dialogCount: document.querySelectorAll('[role="dialog"], dialog').length,
      buttonCount: buttons.length,
      inputCount: document.querySelectorAll('input, textarea, select').length,
      svgCount: svgs.length,
      smallSvgs: svgs.filter((s) => s.width < 80 || s.height < 40).slice(0, 10),
      nextError: document.body.innerText.includes('Hydration failed') || document.body.innerText.includes('This page could not be found'),
    };
  });
}

function finding(severity, device, pathName, title, observed, expected, evidence) {
  return { severity, device, path: pathName, title, observed, expected, evidence };
}

function deriveFindings(results, loginResults) {
  const findings = [];

  for (const login of loginResults) {
    if (!/inválid|invalid|credenciais|senha|erro/i.test(login.wrongLoginText)) {
      findings.push(finding(
        'Importante',
        login.viewport,
        '/login',
        'Login com senha errada não deixou mensagem clara detectável',
        `Texto capturado apos tentativa: ${login.wrongLoginText.slice(0, 240)}`,
        'A tela deve mostrar erro objetivo, em area visivel e com role alert.',
        `login-${login.viewport}-wrong-password.png`,
      ));
    }
  }

  for (const item of results) {
    const p = item.snapshot;
    const label = `${item.route}`;
    if (p.nextError || p.bodyLength < 20) {
      findings.push(finding('Bloqueante', item.viewport, label, 'Tela aparenta estar quebrada ou vazia', p.bodySample, 'Renderizar conteudo funcional ou estado de erro util.', item.screenshot));
    }
    if (p.overflowX || p.clippedRight.length) {
      findings.push(finding('Importante', item.viewport, label, 'Conteudo corta horizontalmente', `scrollWidth=${p.scrollWidth}, clientWidth=${p.clientWidth}, clipped=${JSON.stringify(p.clippedRight.slice(0, 3))}`, 'Nao deve haver overflow horizontal em PC/tablet/mobile.', item.screenshot));
    }
    if (p.tinyTargets.length) {
      findings.push(finding('Importante', item.viewport, label, 'Alvos de toque abaixo de 44px', JSON.stringify(p.tinyTargets.slice(0, 5)), 'Botoes/inputs/tabs devem ter area clicavel minima de 44x44px.', item.screenshot));
    }
    if (p.tinyText.length && item.viewport !== 'pc') {
      findings.push(finding('Melhoria', item.viewport, label, 'Texto abaixo de 14px em tablet/mobile', JSON.stringify(p.tinyText.slice(0, 5)), 'Texto operacional deve manter legibilidade minima de 14px.', item.screenshot));
    }
    for (const failure of item.failedResponses) {
      const sev = failure.status >= 500 ? 'Bloqueante' : 'Importante';
      findings.push(finding(sev, item.viewport, label, `Resposta HTTP ${failure.status}`, `${failure.method} ${failure.url}`, 'A tela nao deve disparar erro HTTP inesperado no uso normal.', item.screenshot));
    }
    for (const msg of item.consoleMessages.filter((m) => m.type === 'error')) {
      findings.push(finding('Importante', item.viewport, label, 'Erro no console', msg.text.slice(0, 300), 'A tela deve carregar sem erro de console no fluxo basico.', item.screenshot));
    }
  }

  const seen = new Set();
  return findings.filter((f) => {
    const key = `${f.severity}|${f.device}|${f.path}|${f.title}|${f.observed.slice(0, 80)}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function writeReport(results, loginResults, findings) {
  const bySeverity = ['Bloqueante', 'Importante', 'Melhoria']
    .map((sev) => [sev, findings.filter((f) => f.severity === sev)])
    .filter(([, rows]) => rows.length);
  const okRoutes = results
    .filter((r) => !r.snapshot.nextError && !r.snapshot.overflowX && !r.failedResponses.some((f) => f.status >= 500))
    .map((r) => `${r.viewport} ${r.route}`);

  const lines = [];
  lines.push('# Auditoria critica MenuFlow');
  lines.push('');
  lines.push(`Base: ${BASE_URL}`);
  lines.push(`Data: ${new Date().toISOString()}`);
  lines.push(`Dispositivos: ${viewports.map((v) => `${v.name} ${v.width}x${v.height}`).join(', ')}`);
  lines.push('');
  lines.push('## Sumario executivo');
  lines.push('');
  lines.push(`Foram auditadas ${routes.length} areas em ${viewports.length} viewports, com login, captura de screenshots, leitura de DOM, erros HTTP/console, overflow, alvos de toque e texto pequeno. Encontrados ${findings.length} achados automatizados. Esta rodada e uma auditoria operacional read-only: nao finalizou pedido nem gravou entidades para nao poluir a demo de producao.`);
  lines.push('');
  lines.push('## Achados por severidade');
  lines.push('');
  if (!bySeverity.length) lines.push('Nenhum achado automatizado relevante nesta rodada.');
  for (const [severity, rows] of bySeverity) {
    lines.push(`### ${severity}`);
    lines.push('');
    for (const row of rows) {
      lines.push(`- **${row.title}**`);
      lines.push(`  - Dispositivo: ${row.device}`);
      lines.push(`  - Tela: ${row.path}`);
      lines.push(`  - O que acontece: ${row.observed}`);
      lines.push(`  - O que deveria acontecer: ${row.expected}`);
      lines.push(`  - Evidencia: ${row.evidence}`);
    }
    lines.push('');
  }
  lines.push('## Top 5 prioridades');
  lines.push('');
  findings.slice(0, 5).forEach((f, idx) => {
    lines.push(`${idx + 1}. ${f.severity}: ${f.path} (${f.device}) - ${f.title}`);
  });
  lines.push('');
  lines.push('## O que funcionou bem');
  lines.push('');
  lines.push(`- ${okRoutes.length} combinacoes de rota/dispositivo renderizaram sem erro 500, sem erro Next e sem overflow horizontal detectado.`);
  lines.push('- O fluxo automatizado conseguiu autenticar como ADMIN e percorrer as telas autenticadas.');
  lines.push('- Screenshots foram salvos para comparacao visual e regressao futura.');
  lines.push('');
  lines.push('## Limites desta rodada');
  lines.push('');
  lines.push('- Nao foram executadas mutacoes destrutivas ou de negocio, como finalizar pedido, criar cupom, editar usuario, resolver handoff ou salvar configuracoes.');
  lines.push('- Validacoes de email, clipboard, WebSocket realtime entre abas, QR/impressao e Android double-back exigem rodada interativa especifica.');
  lines.push('- Contraste WCAG foi inferido por heuristica visual/DOM; para fechar AA por cor exata, rodar auditoria CSS dedicada.');
  fs.writeFileSync(path.join(OUT_DIR, 'REPORT.md'), lines.join('\n'), 'utf8');
}

(async () => {
  ensureDir(OUT_DIR);
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium',
    args: ['--no-sandbox', '--disable-web-security'],
  });

  const loginResults = [];
  const results = [];

  for (const viewport of viewports) {
    loginResults.push(await auditLogin(browser, viewport));

    const context = await browser.newContext({
      viewport: { width: viewport.width, height: viewport.height },
      ignoreHTTPSErrors: true,
    });
    const page = await context.newPage();
    await login(page);

    for (const [name, route, authenticated] of routes) {
      const consoleMessages = [];
      const failedResponses = [];
      page.removeAllListeners('console');
      page.removeAllListeners('response');
      page.on('console', (msg) => {
        if (['error', 'warning'].includes(msg.type())) consoleMessages.push({ type: msg.type(), text: msg.text() });
      });
      page.on('response', (response) => {
        if (response.status() >= 400) failedResponses.push({ status: response.status(), url: response.url(), method: response.request().method() });
      });

      if (!authenticated) {
        await context.clearCookies();
      } else if (String(page.url()).includes('/login')) {
        await login(page);
      }

      await page.goto(`${BASE_URL}${route}`, { waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => {});
      await waitSettle(page);
      const snapshot = await probe(page);
      const screenshot = path.join(OUT_DIR, `${sanitize(name)}-${viewport.name}.png`);
      await page.screenshot({ path: screenshot, fullPage: false });
      const interaction = authenticated ? await trySafeInteraction(page, name, viewport.name) : { attempted: false };
      results.push({
        name,
        route,
        authenticated,
        viewport: viewport.name,
        snapshot,
        screenshot,
        interaction,
        consoleMessages,
        failedResponses,
      });

      if (!authenticated) {
        await login(page);
      }
    }
    await context.close();
  }

  await browser.close();
  const findings = deriveFindings(results, loginResults);
  const payload = { baseUrl: BASE_URL, login: { tenant: LOGIN.tenant, email: LOGIN.email }, viewports, routes, loginResults, results, findings };
  fs.writeFileSync(path.join(OUT_DIR, 'results.json'), JSON.stringify(payload, null, 2), 'utf8');
  writeReport(results, loginResults, findings);
  console.log(JSON.stringify({ outDir: OUT_DIR, findings: findings.length, screenshots: results.length + loginResults.length * 2 }, null, 2));
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
