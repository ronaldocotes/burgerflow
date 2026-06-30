const fs = require('fs');
const path = require('path');

const API_URL = process.env.MF_API_URL || 'http://localhost:8088/api/v1';
const OUT_DIR = path.resolve(__dirname, '..', 'outputs', 'menuflow-phase4-backend-security');
const LOGIN = {
  tenant: process.env.MF_TENANT || 'audit',
  adminEmail: process.env.MF_EMAIL || 'audit@menuflow.local',
  adminPassword: process.env.MF_PASSWORD || 'Audit@1234',
  operatorEmail: process.env.MF_OPERATOR_EMAIL || 'audit.operator@menuflow.local',
  staffEmail: process.env.MF_STAFF_EMAIL || 'audit.staff@menuflow.local',
};
const PASSWORD = process.env.MF_RBAC_PASSWORD || 'Audit@1234';
const RUN_ID = process.env.MF_AUDIT_RUN_ID || new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
const AUDIT_PREFIX = `AUDIT4-${RUN_ID}`;

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

async function login(email, password = PASSWORD) {
  const { body } = await request('POST', '/auth/login', {
    body: { tenantSlug: LOGIN.tenant, email, password },
    expect: [200],
  });
  const token = body?.accessToken || body?.token;
  if (!token) throw new Error(`Login OK para ${email}, mas token nao veio no payload.`);
  return token;
}

function content(pageOrList) {
  if (Array.isArray(pageOrList)) return pageOrList;
  return pageOrList?.content || [];
}

function todaySaoPaulo() {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'America/Sao_Paulo',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
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

  const adminToken = await step('login-admin-audit', () => login(LOGIN.adminEmail, process.env.MF_PASSWORD || PASSWORD));
  const operatorToken = await step('login-operator-audit', () => login(LOGIN.operatorEmail));
  const staffToken = await step('login-staff-audit', () => login(LOGIN.staffEmail));

  await step('protected-products-without-token-returns-401', async () => {
    const res = await request('GET', '/products', { expect: [401] });
    return { status: res.status };
  });

  await step('operator-can-open-pdv-active-orders', async () => {
    const res = await request('GET', '/pdv/orders/active', { token: operatorToken, expect: [200] });
    return { status: res.status, count: Array.isArray(res.body) ? res.body.length : null };
  });

  await step('operator-cannot-read-dre', async () => {
    const today = todaySaoPaulo();
    const res = await request('GET', `/dre?start=${today}&end=${today}`, { token: operatorToken, expect: [403] });
    return { status: res.status };
  });

  await step('staff-cannot-create-product', async () => {
    const categories = content((await request('GET', '/categories', { token: adminToken })).body);
    assertStep(categories.length > 0, 'Tenant audit sem categoria para testar RBAC negativo.');
    const payload = {
      categoryId: categories[0].id,
      sku: `${AUDIT_PREFIX}-STAFF-DENIED`,
      name: `${AUDIT_PREFIX} Staff Denied`,
      priceCents: 1900,
      costPriceCents: 500,
      isAvailable: true,
      displayOrder: 999,
      preparationTimeMinutes: 10,
      isFeatured: false,
    };
    const res = await request('POST', '/products', {
      token: staffToken,
      body: payload,
      headers: { 'Idempotency-Key': `${AUDIT_PREFIX}-staff-denied` },
      expect: [403],
    });
    return { status: res.status };
  });

  const category = await step('create-category-for-idempotency-check', async () => {
    const payload = {
      name: `${AUDIT_PREFIX} Backend`,
      description: 'Categoria criada pela auditoria backend/seguranca Fase 4.',
      displayOrder: 1001,
      colorCode: '#0f766e',
    };
    const { body } = await request('POST', '/categories', { token: adminToken, body: payload, expect: [201] });
    assertStep(body?.id, 'Categoria Fase 4 criada sem id.', body);
    return body;
  });

  const productPayload = {
    categoryId: category.id,
    sku: `${AUDIT_PREFIX}-IDEMP`,
    name: `${AUDIT_PREFIX} Idempotente`,
    description: 'Produto criado para validar idempotencia e misuse.',
    priceCents: 3190,
    costPriceCents: 1100,
    imageUrl: null,
    isAvailable: true,
    displayOrder: 1001,
    preparationTimeMinutes: 11,
    isFeatured: false,
  };

  const product = await step('product-idempotency-replay-same-payload', async () => {
    const key = `${AUDIT_PREFIX}-product-key`;
    const first = await request('POST', '/products', {
      token: adminToken,
      body: productPayload,
      headers: { 'Idempotency-Key': key },
      expect: [201],
    });
    const second = await request('POST', '/products', {
      token: adminToken,
      body: productPayload,
      headers: { 'Idempotency-Key': key },
      expect: [201],
    });
    assertStep(first.body?.id === second.body?.id, 'Replay idempotente nao retornou o mesmo produto.', { first: first.body, second: second.body });
    return first.body;
  });

  await step('product-idempotency-rejects-different-payload', async () => {
    const key = `${AUDIT_PREFIX}-product-key`;
    const changed = { ...productPayload, name: `${AUDIT_PREFIX} Produto Alterado`, priceCents: 3290 };
    const res = await request('POST', '/products', {
      token: adminToken,
      body: changed,
      headers: { 'Idempotency-Key': key },
      expect: [409],
    });
    return { status: res.status, body: res.body };
  });

  await step('signed-token-tenant-binding-ignores-spoofed-header', async () => {
    const { body } = await request('GET', `/products/${product.id}`, {
      token: adminToken,
      headers: { 'X-Tenant-ID': 'demo' },
      expect: [200],
    });
    assertStep(body?.id === product.id, 'Header X-Tenant-ID alterou o escopo do token assinado.', body);
    return { productId: body.id, sku: body.sku };
  });

  await step('public-menu-does-not-expose-admin-cost-fields', async () => {
    const { body } = await request('GET', `/public/${LOGIN.tenant}/menu`, { expect: [200] });
    const publicProduct = (body?.products || []).find((p) => p.id === product.id);
    assertStep(publicProduct, 'Produto Fase 4 nao apareceu no cardapio publico.', { productId: product.id });
    const exposed = Object.keys(publicProduct).filter((key) => ['costPriceCents', 'sku', 'createdAt', 'updatedAt'].includes(key));
    assertStep(exposed.length === 0, 'DTO publico expos campo administrativo.', { exposed, publicProduct });
    return { productId: publicProduct.id, keys: Object.keys(publicProduct).sort() };
  });

  await step('dre-invalid-period-fails-closed', async () => {
    const res = await request('GET', '/dre?start=2026-06-30&end=2026-06-01', { token: adminToken, expect: [400] });
    return { status: res.status, body: res.body };
  });

  await step('admin-cannot-assign-super-admin-role', async () => {
    const payload = { email: `${AUDIT_PREFIX.toLowerCase()}-super@menuflow.local`, role: 'SUPER_ADMIN' };
    const res = await request('POST', '/users/invite', { token: adminToken, body: payload, expect: [400] });
    return { status: res.status, body: res.body };
  });

  await step('last-active-admin-cannot-be-disabled', async () => {
    const users = await request('GET', '/users', { token: adminToken, expect: [200] });
    const admin = users.body.find((user) => user.email === LOGIN.adminEmail);
    assertStep(admin?.id, 'Admin audit nao encontrado para teste anti-lockout.', users.body);
    const res = await request('PATCH', `/users/${admin.id}/status`, {
      token: adminToken,
      body: { active: false },
      expect: [409],
    });
    return { status: res.status, body: res.body };
  });

  artifacts.created = { categoryId: category.id, productId: product.id, prefix: AUDIT_PREFIX };

  const result = {
    apiUrl: API_URL,
    tenant: LOGIN.tenant,
    runId: RUN_ID,
    prefix: AUDIT_PREFIX,
    generatedAt: new Date().toISOString(),
    steps,
    failed: steps.filter((s) => s.status !== 'passed').length,
    artifacts,
  };
  fs.writeFileSync(path.join(OUT_DIR, 'results.json'), JSON.stringify(result, null, 2));
  fs.writeFileSync(path.join(OUT_DIR, 'REPORT.md'), renderReport(result));
  console.log(JSON.stringify({
    outDir: OUT_DIR,
    prefix: AUDIT_PREFIX,
    steps: steps.length,
    failed: result.failed,
    created: artifacts.created,
  }, null, 2));
  if (result.failed > 0) process.exitCode = 1;
}

function renderReport(result) {
  const lines = [];
  lines.push('# MenuFlow Fase 4 - Backend, Dados e Seguranca');
  lines.push('');
  lines.push(`API: ${result.apiUrl}`);
  lines.push(`Data: ${result.generatedAt}`);
  lines.push(`Tenant: ${result.tenant}`);
  lines.push(`Prefixo: \`${result.prefix}\``);
  lines.push('');
  lines.push('## Resultado');
  lines.push('');
  lines.push(`Etapas executadas: ${result.steps.length}`);
  lines.push(`Falhas: ${result.failed}`);
  lines.push('');
  for (const step of result.steps) {
    lines.push(`- ${step.status.toUpperCase()}: ${step.name}`);
  }
  lines.push('');
  lines.push('## Entidades AUDIT Criadas');
  lines.push('');
  lines.push(`- Categoria: \`${result.artifacts.created.categoryId}\``);
  lines.push(`- Produto: \`${result.artifacts.created.productId}\``);
  lines.push('');
  lines.push('## Validacoes Realizadas');
  lines.push('');
  lines.push('- Rotas protegidas retornam 401 sem Bearer.');
  lines.push('- OPERATOR acessa PDV ativo, mas nao acessa DRE.');
  lines.push('- STAFF nao cria produto.');
  lines.push('- Idempotency-Key re-serve mesma resposta para payload igual.');
  lines.push('- Idempotency-Key com payload diferente retorna 409.');
  lines.push('- Header X-Tenant-ID spoofado nao muda o tenant do JWT assinado.');
  lines.push('- Cardapio publico nao expoe custo, SKU nem timestamps internos.');
  lines.push('- DRE com periodo invalido falha fechado.');
  lines.push('- Admin de restaurante nao atribui SUPER_ADMIN.');
  lines.push('- Sistema bloqueia desativar o ultimo admin ativo.');
  lines.push('');
  lines.push('## Limites');
  lines.push('');
  lines.push('- Esta fase cria uma categoria e um produto reais no tenant de auditoria para preservar evidencias.');
  lines.push('- Nao executa testes destrutivos de exclusao nem altera usuarios de outros tenants.');
  lines.push('- Performance/EXPLAIN e varredura de dependencias ficam para uma rodada dedicada da Fase 4.');
  return lines.join('\n');
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
