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

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = await getToken();
  const res = await fetch(`${AppConfig.API_URL}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    const msg = await res.text().catch(() => res.statusText);
    throw new ApiError(res.status, msg);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}

// Exports nomeados (usados pelas telas, ex.: LoginScreen importa { post }).
export const get = <T>(path: string) => request<T>('GET', path);
export const post = <T>(path: string, body: unknown, _headers?: Record<string, string>) =>
  request<T>('POST', path, body);
export const put = <T>(path: string, body: unknown) => request<T>('PUT', path, body);
export const patch = <T>(path: string, body: unknown) => request<T>('PATCH', path, body);

// Objeto agregador (acucar sintatico: api.get/api.post/...).
export const api = { get, post, put, patch };
