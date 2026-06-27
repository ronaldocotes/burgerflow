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
