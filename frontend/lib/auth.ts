// Autenticação: login contra POST /auth/login (TokenResponse) e gestão do token
// no localStorage (compatível com hooks/use-auth, que lê menuflow_access_token).

import { api, TOKEN_KEY } from "./api";

const REFRESH_KEY = "menuflow_refresh_token";
const TENANT_KEY = "menuflow_tenant";

interface TokenResponse {
  token: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

export async function login(
  email: string,
  password: string,
  tenantSlug: string,
): Promise<void> {
  const res = await api.post<TokenResponse>("/auth/login", {
    email,
    password,
    tenantSlug,
  });
  window.localStorage.setItem(TOKEN_KEY, res.token);
  window.localStorage.setItem(REFRESH_KEY, res.refreshToken);
  window.localStorage.setItem(TENANT_KEY, tenantSlug);
}

export function logout(): void {
  window.localStorage.removeItem(TOKEN_KEY);
  window.localStorage.removeItem(REFRESH_KEY);
}

export function getToken(): string | null {
  return typeof window !== "undefined"
    ? window.localStorage.getItem(TOKEN_KEY)
    : null;
}

export function getTenant(): string | null {
  return typeof window !== "undefined"
    ? window.localStorage.getItem(TENANT_KEY)
    : null;
}

/**
 * Decodifica o papel (role) do JWT salvo no localStorage. Mesmo padrão inline
 * usado em `components/layout/Sidebar.tsx` e `hooks/useSuperAdminGuard.ts`
 * (não deduplicado ali para não arriscar essas duas telas já em produção) —
 * aqui centralizado para o RBAC de UI de /pedidos (fatia 3 do Novo Pedido).
 * RBAC real (deny-by-default) é do backend (@PreAuthorize); isto é só a UI
 * escondendo uma ação que o servidor recusaria de qualquer forma.
 */
export function getUserRole(): string | null {
  try {
    const token = getToken();
    if (!token) return null;
    const parts = token.split(".");
    if (parts.length !== 3) return null;
    const b64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const payload = JSON.parse(atob(b64)) as { role?: string; roles?: string[] };
    return payload.role ?? ((Array.isArray(payload.roles) && payload.roles[0]) || null);
  } catch {
    return null;
  }
}
