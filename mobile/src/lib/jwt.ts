// Decodifica o payload do JWT LOCALMENTE (sem validar assinatura — validacao e
// papel do backend). Uso: descobrir as roles para rotear DRIVER -> abas do motoboy.

const B64_ALPHABET =
  'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

// Hermes (RN >= 0.74) tem atob global, mas usamos um decoder puro-JS para nao
// depender de detalhe de runtime. As claims que lemos (roles) sao ASCII.
function base64Decode(input: string): string {
  const clean = input.replace(/=+$/, '');
  let out = '';
  let bits = 0;
  let buffer = 0;
  for (const ch of clean) {
    const idx = B64_ALPHABET.indexOf(ch);
    if (idx === -1) continue;
    buffer = (buffer << 6) | idx;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buffer >> bits) & 0xff);
    }
  }
  return out;
}

export interface JwtPayload {
  sub?: string;
  tenantId?: string;
  tenantUuid?: string;
  roles?: string[];
  type?: string;
  exp?: number;
}

export function decodeJwtPayload(token: string): JwtPayload | null {
  try {
    const part = token.split('.')[1];
    if (!part) return null;
    const b64 = part.replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(base64Decode(b64)) as JwtPayload;
  } catch {
    return null;
  }
}

export function rolesOf(token: string | null): string[] {
  if (!token) return [];
  return decodeJwtPayload(token)?.roles ?? [];
}

/** True quando o token pertence a um entregador (role DRIVER). */
export function isDriverToken(token: string | null): boolean {
  return rolesOf(token).includes('DRIVER');
}
