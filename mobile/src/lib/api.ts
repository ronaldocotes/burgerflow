import { AppConfig } from '@/config';
import { getToken } from './auth';

// Cliente HTTP do backend MenuFlow. Bearer token vem do EncryptedStorage (async).
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

// Handler global de 401 (sessao invalida/revogada). Registrado pelo RootNavigator
// para limpar a sessao e voltar ao Login. Mantido aqui para valer para TODA chamada.
let onUnauthorized: (() => void) | null = null;
export function setOnUnauthorized(handler: (() => void) | null) {
  onUnauthorized = handler;
}

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  extraHeaders?: Record<string, string>,
): Promise<T> {
  const token = await getToken();
  const res = await fetch(`${AppConfig.API_URL}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...extraHeaders,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    // 401 com token presente = sessao morta -> derruba para o Login.
    if (res.status === 401 && token && onUnauthorized) onUnauthorized();
    const msg = await res.text().catch(() => res.statusText);
    throw new ApiError(res.status, msg);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}

// Exports nomeados (usados pelas telas, ex.: LoginScreen importa { post }).
export const get = <T>(path: string) => request<T>('GET', path);
export const post = <T>(
  path: string,
  body: unknown,
  extraHeaders?: Record<string, string>,
) => request<T>('POST', path, body, extraHeaders);
export const put = <T>(path: string, body: unknown) => request<T>('PUT', path, body);
export const patch = <T>(path: string, body: unknown) => request<T>('PATCH', path, body);

// Objeto agregador (acucar sintatico: api.get/api.post/...).
export const api = { get, post, put, patch };
