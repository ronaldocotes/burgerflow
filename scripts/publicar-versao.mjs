#!/usr/bin/env node
// Publica uma versao do app do motoboy no servidor (POST /api/v1/admin/app/releases).
// Molde SISATER (scripts/publicar-versao.mjs), adaptado ao MenuFlow: rota /admin/app/releases,
// login com email+senha+tenantSlug (o binario e guardado em BYTEA no banco de CONTROLE).
//
// O APK vai no corpo como application/octet-stream (--data-binary), nao multipart, para nao
// esbarrar no limite de multipart. So SUPER_ADMIN publica. Depois disto, os apps instalados com
// versionCode menor verao o aviso de atualizacao (GET /public/app/latest) e poderao baixar.
//
// Uso:
//   node scripts/publicar-versao.mjs --apk caminho.apk --code 3 --name 1.2.0 \
//        --email admin@loja.com --senha 'segredo' --tenant minha-loja \
//        [--notas "o que mudou"] [--obrigatoria] [--plataforma android] \
//        [--base http://localhost:8080]
import fs from 'node:fs';

const args = process.argv.slice(2);
function opt(nome, def) {
  const i = args.indexOf(`--${nome}`);
  if (i < 0) return def;
  const v = args[i + 1];
  return v === undefined || v.startsWith('--') ? true : v; // sem valor = flag booleana
}

const apkPath = opt('apk');
const versionCode = opt('code');
const versionName = opt('name');
const notas = opt('notas', '');
const obrigatoria = !!opt('obrigatoria', false);
const plataforma = String(opt('plataforma', 'android'));
const base = String(opt('base', 'http://localhost:8080')).replace(/\/$/, '');
const email = opt('email');
const senha = opt('senha');
const tenant = opt('tenant');

if (!apkPath || !versionCode || !versionName || !email || !senha || !tenant) {
  console.error(
    'Faltam args. Ex.: node scripts/publicar-versao.mjs --apk app.apk --code 3 --name 1.2.0 ' +
      "--email admin@loja.com --senha 'segredo' --tenant minha-loja [--notas '...'] [--obrigatoria]",
  );
  process.exit(1);
}
if (!fs.existsSync(apkPath)) {
  console.error('APK nao encontrado:', apkPath);
  process.exit(1);
}

const API = `${base}/api/v1`;

const login_r = await fetch(`${API}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email, password: senha, tenantSlug: tenant }),
});
if (!login_r.ok) {
  console.error('Falha no login:', login_r.status, await login_r.text());
  process.exit(1);
}
const loginBody = await login_r.json();
if (loginBody.status === '2FA_REQUIRED') {
  console.error('Este SUPER_ADMIN tem 2FA ativo; publique por um usuario/fluxo que complete o 2FA.');
  process.exit(1);
}
const token = loginBody.token;

const apk = fs.readFileSync(apkPath);
const qs = new URLSearchParams({
  versionCode: String(versionCode),
  versionName: String(versionName),
  plataforma,
  obrigatoria: String(obrigatoria),
});
if (notas) qs.set('notas', String(notas));

console.log(`Enviando ${apkPath} (${(apk.length / 1e6).toFixed(1)} MB) como versao ${versionName} (code ${versionCode})...`);
const up = await fetch(`${API}/admin/app/releases?${qs}`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/octet-stream' },
  body: apk,
});
const txt = await up.text();
if (!up.ok) {
  console.error('Falha ao publicar:', up.status, txt);
  process.exit(1);
}
console.log('Publicado com sucesso:', txt);
