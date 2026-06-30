const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright');

const BASE_URL = process.env.BASE_URL || 'http://localhost:3011';
const OUT_DIR = path.resolve(__dirname, '..', 'outputs', 'menuflow-phase2-safe-flows');
const LOGIN = {
  tenant: process.env.MF_TENANT || 'audit',
  email: process.env.MF_EMAIL || 'audit@menuflow.local',
  password: process.env.MF_PASSWORD || 'Audit@1234',
};

const viewports = [
  { name: 'pc', width: 1920, height: 1080 },
  { name: 'tablet', width: 768, height: 1024 },
  { name: 'mobile', width: 390, height: 844 },
];

const flows = [
  {
    name: 'sidebar-mobile-open-close',
    route: '/pdv',
    auth: true,
    run: async (page, step) => {
      const menu = page.getByRole('button', { name: /abrir menu/i }).first();
      if (!(await menu.isVisible().catch(() => false))) return step('skipped', 'Botao de menu mobile nao visivel neste viewport.');
      await menu.click();
      await page.waitForTimeout(300);
      const opened = await page.getByRole('dialog', { name: /menu/i }).isVisible().catch(() => false);
      await step(opened ? 'passed' : 'failed', opened ? 'Drawer mobile abriu.' : 'Drawer mobile nao abriu.');
      await page.getByRole('button', { name: /fechar menu/i }).click().catch(() => page.keyboard.press('Escape'));
    },
  },
  {
    name: 'admin-cardapio-new-product',
    route: '/admin/cardapio',
    auth: true,
    run: async (page, step) => {
      await clickAndExpectUi(page, /novo produto/i, /novo produto|produto/i, step);
    },
  },
  {
    name: 'admin-usuarios-invite',
    route: '/admin/usuarios',
    auth: true,
    run: async (page, step) => {
      await clickAndExpectUi(page, /convidar usuario|convidar usuário/i, /convite|email|papel/i, step);
    },
  },
  {
    name: 'financeiro-dre-new-expense',
    route: '/financeiro/dre',
    auth: true,
    run: async (page, step) => {
      await clickAndExpectUi(page, /nova despesa/i, /despesa|valor|categoria/i, step);
    },
  },
  {
    name: 'admin-cupons-new-coupon',
    route: '/admin/cupons',
    auth: true,
    run: async (page, step) => {
      await clickAndExpectUi(page, /novo cupom|criar cupom/i, /cupom|codigo|código|desconto/i, step);
    },
  },
  {
    name: 'cardapio-public-product-and-cart',
    route: '/cardapio',
    auth: false,
    run: async (page, step) => {
      const productCard = page.locator('.pos-product-card').first();
      if (!(await productCard.isVisible().catch(() => false))) return step('failed', 'Nao encontrei card de produto no cardapio publico.');
      await productCard.click();
      await page.waitForTimeout(500);
      const hasProductUi = await visibleText(page, /adicionar|observa|complemento|quantidade|carrinho/i);
      await step(hasProductUi ? 'passed' : 'failed', hasProductUi ? 'Produto abriu em estado intermediario.' : 'Clique no produto nao exibiu estado intermediario claro.');
      await page.keyboard.press('Escape').catch(() => {});
    },
  },
  {
    name: 'pdv-add-item-local-cart',
    route: '/pdv',
    auth: true,
    run: async (page, step) => {
      const textBefore = await page.locator('body').innerText().catch(() => '');
      const firstProduct = page.locator('button, article').filter({ hasText: /classic|pizza|combo|x-/i }).first();
      if (!(await firstProduct.isVisible().catch(() => false))) return step('failed', 'Nao encontrei item vendavel no PDV.');
      await firstProduct.click();
      await page.waitForTimeout(600);
      const addButton = page.getByRole('button', { name: /adicionar|incluir|confirmar/i }).first();
      if (await addButton.isVisible().catch(() => false)) {
        await addButton.click().catch(() => {});
        await page.waitForTimeout(400);
      }
      const textAfter = await page.locator('body').innerText().catch(() => '');
      const changed = textAfter !== textBefore && /carrinho|total|item|pedido/i.test(textAfter);
      await step(changed ? 'passed' : 'warning', changed ? 'Item foi para estado local de carrinho sem fechar pedido.' : 'PDV respondeu ao clique, mas a mudanca no carrinho nao ficou inequívoca.');
    },
  },
  {
    name: 'copilot-open-close',
    route: '/pdv',
    auth: true,
    run: async (page, step) => {
      const buttons = await page.getByRole('button').all();
      for (const button of buttons.reverse()) {
        const label = await accessibleName(button);
        if (!/copilot|copiloto|ia|assistente/i.test(label)) continue;
        await button.click();
        await page.waitForTimeout(500);
        const opened = await visibleText(page, /copilot|copiloto|assistente|pergunte|mensagem/i);
        await step(opened ? 'passed' : 'failed', opened ? 'Copiloto abriu.' : 'Botao encontrado, mas painel nao ficou visivel.');
        await page.keyboard.press('Escape').catch(() => {});
        return;
      }
      await step('warning', 'Nao encontrei botao do Copiloto por nome acessivel.');
    },
  },
  {
    name: 'admin-bot-schedule-view',
    route: '/admin/bot',
    auth: true,
    run: async (page, step) => {
      const hasSchedule = await visibleText(page, /segunda|terça|terca|domingo|horario|horário|whatsapp/i);
      await step(hasSchedule ? 'passed' : 'failed', hasSchedule ? 'Bot WhatsApp mostra agenda/estado operacional.' : 'Bot WhatsApp nao mostrou agenda ou estado operacional claro.');
    },
  },
];

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

function sanitize(name) {
  return name.replace(/[^a-z0-9_-]+/gi, '-').toLowerCase();
}

async function waitSettle(page) {
  await page.waitForLoadState('domcontentloaded').catch(() => {});
  await page.waitForLoadState('networkidle', { timeout: 7000 }).catch(() => {});
  await page.waitForTimeout(500);
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

async function accessibleName(locator) {
  return (
    (await locator.innerText().catch(() => '')) ||
    (await locator.getAttribute('aria-label').catch(() => '')) ||
    (await locator.getAttribute('title').catch(() => '')) ||
    ''
  ).replace(/\s+/g, ' ').trim();
}

async function visibleText(page, pattern) {
  const body = await page.locator('body').innerText().catch(() => '');
  return pattern.test(body);
}

async function clickAndExpectUi(page, buttonPattern, expectedTextPattern, step) {
  const button = page.getByRole('button', { name: buttonPattern }).first();
  if (!(await button.isVisible().catch(() => false))) return step('failed', `Botao ${buttonPattern} nao encontrado.`);
  await button.click();
  await page.waitForTimeout(700);
  const hasDialog = await page.locator('[role="dialog"], dialog').first().isVisible().catch(() => false);
  const hasExpectedText = await visibleText(page, expectedTextPattern);
  await step(hasDialog || hasExpectedText ? 'passed' : 'failed', hasDialog ? 'Abriu dialog/sheet seguro.' : hasExpectedText ? 'Abriu formulario/estado intermediario seguro.' : 'Clique nao revelou estado intermediario esperado.');
  await page.keyboard.press('Escape').catch(() => {});
  await page.getByRole('button', { name: /cancelar|fechar/i }).first().click().catch(() => {});
}

async function createAuthState(browser, viewport) {
  const context = await browser.newContext({
    viewport: { width: viewport.width, height: viewport.height },
    ignoreHTTPSErrors: true,
  });
  const page = await context.newPage();
  page.setDefaultTimeout(5000);
  await login(page);
  const storageState = await context.storageState();
  await context.close();
  return storageState;
}

async function runFlow(browser, viewport, flow, storageState) {
  const context = await browser.newContext({
    viewport: { width: viewport.width, height: viewport.height },
    ignoreHTTPSErrors: true,
    ...(flow.auth ? { storageState } : {}),
  });
  const page = await context.newPage();
  page.setDefaultTimeout(4000);
  page.setDefaultNavigationTimeout(12000);
  const consoleMessages = [];
  const failedResponses = [];
  page.on('console', (msg) => {
    if (['error', 'warning'].includes(msg.type())) consoleMessages.push({ type: msg.type(), text: msg.text() });
  });
  page.on('response', (response) => {
    if (response.status() >= 400) failedResponses.push({ status: response.status(), url: response.url(), method: response.request().method() });
  });

  await page.goto(`${BASE_URL}${flow.route}`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await waitSettle(page);

  const steps = [];
  const step = async (status, message) => {
    steps.push({ status, message });
  };

  const before = path.join(OUT_DIR, `${sanitize(flow.name)}-${viewport.name}-before.png`);
  await page.screenshot({ path: before, fullPage: false });

  let error = null;
  try {
    await Promise.race([
      flow.run(page, step),
      new Promise((_, reject) => setTimeout(() => reject(new Error(`Timeout no fluxo ${flow.name}`)), 12000)),
    ]);
  } catch (err) {
    error = err.message;
    steps.push({ status: 'failed', message: err.message });
  }

  const after = path.join(OUT_DIR, `${sanitize(flow.name)}-${viewport.name}-after.png`);
  await page.screenshot({ path: after, fullPage: false });
  const bodySample = (await page.locator('body').innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 1200);
  await context.close();

  const status = error || steps.some((s) => s.status === 'failed')
    ? 'failed'
    : steps.some((s) => s.status === 'warning' || s.status === 'skipped')
      ? 'warning'
      : 'passed';

  return {
    flow: flow.name,
    route: flow.route,
    viewport: viewport.name,
    status,
    steps,
    error,
    before,
    after,
    consoleMessages,
    failedResponses,
    bodySample,
  };
}

function writeReport(results) {
  const counts = results.reduce((acc, row) => {
    acc[row.status] = (acc[row.status] || 0) + 1;
    return acc;
  }, {});
  const lines = [];
  lines.push('# MenuFlow Fase 2 - Fluxos Operacionais Seguros');
  lines.push('');
  lines.push(`Base: ${BASE_URL}`);
  lines.push(`Data: ${new Date().toISOString()}`);
  lines.push(`Usuario: ${LOGIN.tenant}/${LOGIN.email}`);
  lines.push('');
  lines.push('## Sumario');
  lines.push('');
  lines.push(`Foram executados ${results.length} cenarios em ${viewports.length} viewports, sem salvar formulario, fechar pedido, alterar configuracao ou executar acao destrutiva.`);
  lines.push(`Resultado: ${counts.passed || 0} passed, ${counts.warning || 0} warning, ${counts.failed || 0} failed.`);
  lines.push('');
  lines.push('## Cenarios');
  lines.push('');
  for (const row of results) {
    lines.push(`### ${row.status.toUpperCase()} - ${row.viewport} - ${row.flow}`);
    lines.push('');
    lines.push(`- Rota: \`${row.route}\``);
    lines.push(`- Antes: \`${path.relative(path.dirname(path.join(OUT_DIR, 'REPORT.md')), row.before)}\``);
    lines.push(`- Depois: \`${path.relative(path.dirname(path.join(OUT_DIR, 'REPORT.md')), row.after)}\``);
    for (const step of row.steps) lines.push(`- ${step.status}: ${step.message}`);
    if (row.failedResponses.length) lines.push(`- HTTP >=400: ${JSON.stringify(row.failedResponses.slice(0, 5))}`);
    const consoleErrors = row.consoleMessages.filter((m) => m.type === 'error');
    if (consoleErrors.length) lines.push(`- Console errors: ${JSON.stringify(consoleErrors.slice(0, 5))}`);
    lines.push('');
  }
  lines.push('## Limites');
  lines.push('');
  lines.push('- Esta fase valida abertura e resposta visual de fluxos intermediarios, nao persistencia de entidades.');
  lines.push('- Fluxos que exigem criacao real entram na Fase 3 com prefixo `AUDIT-`.');
  fs.writeFileSync(path.join(OUT_DIR, 'REPORT.md'), lines.join('\n'), 'utf8');
}

(async () => {
  fs.rmSync(OUT_DIR, { recursive: true, force: true });
  ensureDir(OUT_DIR);
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROMIUM_PATH || '/usr/bin/chromium',
    args: ['--no-sandbox', '--disable-web-security'],
  });
  const results = [];
  for (const viewport of viewports) {
    const storageState = await createAuthState(browser, viewport);
    for (const flow of flows) {
      results.push(await runFlow(browser, viewport, flow, storageState));
    }
  }
  await browser.close();
  fs.writeFileSync(path.join(OUT_DIR, 'results.json'), JSON.stringify({ baseUrl: BASE_URL, login: { tenant: LOGIN.tenant, email: LOGIN.email }, viewports, flows: flows.map((f) => ({ name: f.name, route: f.route, auth: f.auth })), results }, null, 2), 'utf8');
  writeReport(results);
  const counts = results.reduce((acc, row) => {
    acc[row.status] = (acc[row.status] || 0) + 1;
    return acc;
  }, {});
  console.log(JSON.stringify({ outDir: OUT_DIR, total: results.length, counts }, null, 2));
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
