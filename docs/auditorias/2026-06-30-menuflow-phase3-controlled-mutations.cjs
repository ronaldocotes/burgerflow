const fs = require('fs');
const path = require('path');

const API_URL = process.env.MF_API_URL || 'http://localhost:8088/api/v1';
const OUT_DIR = path.resolve(__dirname, '..', 'outputs', 'menuflow-phase3-controlled-mutations');
const LOGIN = {
  tenant: process.env.MF_TENANT || 'audit',
  email: process.env.MF_EMAIL || 'audit@menuflow.local',
  password: process.env.MF_PASSWORD || 'Audit@1234',
};
const RUN_ID = process.env.MF_AUDIT_RUN_ID || new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
const AUDIT_PREFIX = `AUDIT-${RUN_ID}`;

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

async function request(method, url, { token, body, headers = {}, expect = [200] } = {}) {
  const res = await fetch(url.startsWith('http') ? url : `${API_URL}${url}`, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  if (text) {
    try { json = JSON.parse(text); } catch { json = text; }
  }
  if (!expect.includes(res.status)) {
    const err = new Error(`${method} ${url} -> HTTP ${res.status}: ${text.slice(0, 500)}`);
    err.response = { status: res.status, body: json };
    throw err;
  }
  return { status: res.status, body: json, raw: text };
}

async function login() {
  const { body } = await request('POST', '/auth/login', {
    body: { tenantSlug: LOGIN.tenant, email: LOGIN.email, password: LOGIN.password },
    expect: [200],
  });
  const token = body?.accessToken || body?.token;
  if (!token) throw new Error('Login OK, mas token nao veio no payload.');
  return token;
}

function todaySaoPaulo() {
  const fmt = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  });
  return fmt.format(new Date());
}

function isoOffset(ms) {
  return new Date(Date.now() + ms).toISOString();
}

function content(pageOrList) {
  if (Array.isArray(pageOrList)) return pageOrList;
  return pageOrList?.content || [];
}

function assertStep(condition, message, details = {}) {
  if (!condition) {
    const err = new Error(message);
    err.details = details;
    throw err;
  }
}

async function main() {
  ensureDir(OUT_DIR);
  const steps = [];
  const artifacts = {};

  async function step(name, fn) {
    const startedAt = new Date().toISOString();
    try {
      const data = await fn();
      steps.push({ name, status: 'passed', startedAt, finishedAt: new Date().toISOString(), data });
      return data;
    } catch (error) {
      steps.push({
        name,
        status: 'failed',
        startedAt,
        finishedAt: new Date().toISOString(),
        error: error.message,
        details: error.details || error.response || null,
      });
      throw error;
    }
  }

  const token = await step('login-admin-audit', login);
  const today = todaySaoPaulo();
  const dreBefore = await step('dre-before-today', async () => {
    const { body } = await request('GET', `/dre?start=${today}&end=${today}`, { token });
    return body;
  });

  const category = await step('create-audit-category', async () => {
    const payload = {
      name: `${AUDIT_PREFIX} Categoria`,
      description: 'Categoria criada pela auditoria controlada Fase 3.',
      displayOrder: 999,
      colorCode: '#047857',
    };
    const { body } = await request('POST', '/categories', { token, body: payload, expect: [201] });
    assertStep(body?.id, 'Categoria criada sem id.', body);
    return body;
  });

  const productPayload = {
    categoryId: category.id,
    sku: `${AUDIT_PREFIX}-SKU`,
    name: `${AUDIT_PREFIX} Produto`,
    description: 'Produto criado pela auditoria controlada Fase 3.',
    priceCents: 2590,
    costPriceCents: 900,
    imageUrl: null,
    isAvailable: true,
    displayOrder: 999,
    preparationTimeMinutes: 12,
    isFeatured: true,
  };

  const product = await step('create-audit-product-idempotent', async () => {
    const key = `${AUDIT_PREFIX}-product`;
    const first = await request('POST', '/products', {
      token,
      body: productPayload,
      headers: { 'Idempotency-Key': key },
      expect: [201],
    });
    const second = await request('POST', '/products', {
      token,
      body: productPayload,
      headers: { 'Idempotency-Key': key },
      expect: [201],
    });
    assertStep(first.body?.id === second.body?.id, 'Idempotencia de produto nao retornou o mesmo id.', { first: first.body, second: second.body });
    return { ...first.body, idempotentReplayStatus: second.status };
  });

  await step('validate-public-menu-has-audit-product', async () => {
    const { body } = await request('GET', `/public/${LOGIN.tenant}/menu`);
    const found = (body?.products || []).find((p) => p.id === product.id);
    assertStep(Boolean(found), 'Produto AUDIT nao apareceu no cardapio publico.', { productId: product.id });
    return { productId: found.id, name: found.name, categoryId: found.categoryId };
  });

  const coupon = await step('create-audit-coupon', async () => {
    const payload = {
      code: `${AUDIT_PREFIX}-CUPOM`,
      description: 'Cupom criado pela auditoria controlada Fase 3.',
      discountType: 'FIXED',
      discountValue: 500,
      minOrderCents: 1000,
      maxUses: 5,
      maxUsesPerCustomer: 2,
      validFrom: isoOffset(-60 * 60 * 1000),
      validUntil: isoOffset(7 * 24 * 60 * 60 * 1000),
      active: true,
    };
    const { body } = await request('POST', '/coupons', { token, body: payload, expect: [201] });
    assertStep(body?.id && body?.code === payload.code, 'Cupom criado sem contrato esperado.', body);
    return body;
  });

  await step('preview-audit-coupon-public', async () => {
    const subtotalCents = product.priceCents * 2;
    const { body } = await request('POST', `/public/${LOGIN.tenant}/apply-coupon`, {
      body: { code: coupon.code, subtotalCents, customerPhone: '5596999000001' },
      expect: [200],
    });
    assertStep(body?.valid === true && body?.discountCents === 500, 'Preview do cupom nao retornou desconto esperado.', body);
    return body;
  });

  const expense = await step('create-audit-operating-expense', async () => {
    const payload = {
      description: `${AUDIT_PREFIX} Despesa`,
      amountCents: 1234,
      category: 'OTHER',
      expenseDate: today,
    };
    const { body } = await request('POST', '/operating-expenses', { token, body: payload, expect: [201] });
    assertStep(body?.id && body?.amountCents === payload.amountCents, 'Despesa criada sem contrato esperado.', body);
    return body;
  });

  const publicOrder = await step('create-public-order-with-coupon', async () => {
    const payload = {
      customerName: `${AUDIT_PREFIX} Cliente Publico`,
      paymentMethod: 'PIX',
      tableLabel: 'AUDIT',
      customerPhone: '5596999000001',
      couponCode: coupon.code,
      observations: 'Pedido publico AUDIT controlado.',
      items: [{ productId: product.id, quantity: 2, notes: 'Item AUDIT', optionIds: [] }],
    };
    const { body } = await request('POST', `/public/${LOGIN.tenant}/orders`, { body: payload, expect: [200] });
    assertStep(body?.orderId && body?.totalCents === product.priceCents * 2 - 500, 'Pedido publico nao aplicou total esperado.', body);
    return body;
  });

  await step('validate-coupon-redemption', async () => {
    const { body } = await request('GET', `/coupons/${coupon.id}/redemptions?size=20`, { token });
    const rows = content(body);
    const found = rows.find((r) => r.orderId === publicOrder.orderId);
    assertStep(Boolean(found), 'Redemption do cupom nao foi registrada para o pedido publico.', { orderId: publicOrder.orderId, rows });
    return found;
  });

  await step('validate-kds-has-public-order', async () => {
    const { body } = await request('GET', '/kds/orders', { token });
    const found = (body || []).find((o) => o.id === publicOrder.orderId || o.orderId === publicOrder.orderId);
    assertStep(Boolean(found), 'Pedido publico AUDIT nao apareceu no KDS ativo.', { orderId: publicOrder.orderId, count: (body || []).length });
    return found;
  });

  const pdvOrder = await step('create-pdv-order-idempotent', async () => {
    const payload = {
      tableNumber: 'AUDIT-PDV',
      channel: 'DINE_IN',
      items: [{ productId: product.id, quantity: 1, notes: 'PDV AUDIT', optionIds: [] }],
    };
    const key = `${AUDIT_PREFIX}-pdv-order`;
    const first = await request('POST', '/pdv/orders', {
      token,
      body: payload,
      headers: { 'Idempotency-Key': key },
      expect: [201],
    });
    const second = await request('POST', '/pdv/orders', {
      token,
      body: payload,
      headers: { 'Idempotency-Key': key },
      expect: [201],
    });
    assertStep(first.body?.id === second.body?.id, 'Idempotencia do pedido PDV nao retornou o mesmo id.', { first: first.body, second: second.body });
    return { ...first.body, idempotentReplayStatus: second.status };
  });

  const payment = await step('pay-pdv-order-card', async () => {
    const { body } = await request('POST', `/pdv/orders/${pdvOrder.id}/pay`, {
      token,
      body: { method: 'CARD', amountPaidCents: pdvOrder.totalCents },
      expect: [200],
    });
    assertStep(body?.orderId === pdvOrder.id && body?.changeCents === 0, 'Pagamento PDV nao fechou como esperado.', body);
    return body;
  });

  const dreAfter = await step('dre-after-today', async () => {
    const { body } = await request('GET', `/dre?start=${today}&end=${today}`, { token });
    assertStep(
      body.operatingExpensesCents >= dreBefore.operatingExpensesCents + expense.amountCents,
      'DRE nao refletiu a despesa operacional AUDIT.',
      { before: dreBefore.operatingExpensesCents, after: body.operatingExpensesCents, expense: expense.amountCents },
    );
    assertStep(
      body.grossRevenueCents >= dreBefore.grossRevenueCents + pdvOrder.totalCents,
      'DRE nao refletiu a venda PDV paga.',
      { before: dreBefore.grossRevenueCents, after: body.grossRevenueCents, sale: pdvOrder.totalCents },
    );
    return body;
  });

  artifacts.summary = {
    runId: RUN_ID,
    prefix: AUDIT_PREFIX,
    categoryId: category.id,
    productId: product.id,
    couponId: coupon.id,
    expenseId: expense.id,
    publicOrderId: publicOrder.orderId,
    pdvOrderId: pdvOrder.id,
    paymentId: payment.id,
    dreBefore,
    dreAfter,
  };

  const payload = { apiUrl: API_URL, login: { tenant: LOGIN.tenant, email: LOGIN.email }, runId: RUN_ID, prefix: AUDIT_PREFIX, steps, artifacts };
  fs.writeFileSync(path.join(OUT_DIR, 'results.json'), JSON.stringify(payload, null, 2), 'utf8');
  writeReport(payload);
  console.log(JSON.stringify({ outDir: OUT_DIR, prefix: AUDIT_PREFIX, steps: steps.length, failed: steps.filter((s) => s.status === 'failed').length, summary: artifacts.summary }, null, 2));
}

function writeReport(payload) {
  const lines = [];
  lines.push('# MenuFlow Fase 3 - Mutacao Controlada');
  lines.push('');
  lines.push(`API: ${payload.apiUrl}`);
  lines.push(`Data: ${new Date().toISOString()}`);
  lines.push(`Tenant: ${payload.login.tenant}`);
  lines.push(`Usuario: ${payload.login.email}`);
  lines.push(`Prefixo: \`${payload.prefix}\``);
  lines.push('');
  lines.push('## Resultado');
  lines.push('');
  const failed = payload.steps.filter((s) => s.status === 'failed');
  lines.push(`Etapas executadas: ${payload.steps.length}`);
  lines.push(`Falhas: ${failed.length}`);
  lines.push('');
  for (const step of payload.steps) {
    lines.push(`- ${step.status === 'passed' ? 'PASS' : 'FAIL'}: ${step.name}`);
    if (step.error) lines.push(`  - erro: ${step.error}`);
  }
  lines.push('');
  lines.push('## Entidades AUDIT Criadas');
  lines.push('');
  const s = payload.artifacts.summary;
  lines.push(`- Categoria: \`${s.categoryId}\``);
  lines.push(`- Produto: \`${s.productId}\``);
  lines.push(`- Cupom: \`${s.couponId}\``);
  lines.push(`- Despesa: \`${s.expenseId}\``);
  lines.push(`- Pedido publico: \`${s.publicOrderId}\``);
  lines.push(`- Pedido PDV: \`${s.pdvOrderId}\``);
  lines.push(`- Pagamento PDV: \`${s.paymentId}\``);
  lines.push('');
  lines.push('## Validacoes Realizadas');
  lines.push('');
  lines.push('- Produto idempotente: mesma `Idempotency-Key` retornou o mesmo produto.');
  lines.push('- Produto apareceu no cardapio publico do tenant `audit`.');
  lines.push('- Cupom criado e preview publico retornou desconto esperado.');
  lines.push('- Pedido publico aplicou cupom e registrou redemption.');
  lines.push('- Pedido publico apareceu no KDS ativo.');
  lines.push('- Pedido PDV idempotente retornou o mesmo pedido em replay.');
  lines.push('- Pagamento CARD fechou pedido PDV sem depender de caixa aberto.');
  lines.push('- DRE do dia refletiu despesa operacional e venda paga.');
  lines.push('');
  lines.push('## Limites');
  lines.push('');
  lines.push('- Esta fase cria dados reais no tenant de auditoria. Nao remove os registros para preservar evidencias.');
  lines.push('- Pagamento testado com CARD; venda CASH exige fluxo separado de abertura/fechamento de caixa.');
  fs.writeFileSync(path.join(OUT_DIR, 'REPORT.md'), lines.join('\n'), 'utf8');
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
