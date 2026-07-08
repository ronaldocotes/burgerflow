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
    const message = await extractErrorMessage(res);
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}

// Mensagem padrao por status quando o backend nao envia corpo util.
const STATUS_FALLBACK: Record<number, string> = {
  400: 'Requisicao invalida.',
  401: 'Sessao expirada. Faca login novamente.',
  403: 'Voce nao tem permissao para esta acao.',
  404: 'Recurso nao encontrado.',
  409: 'Conflito com o estado atual do recurso.',
  500: 'Ocorreu um erro inesperado. Tente novamente.',
};

// Extrai uma mensagem legivel do corpo de erro. O backend (Spring
// GlobalExceptionHandler) responde JSON { status, error, code, message, errors[] };
// preferimos .message. Se o corpo nao for JSON, devolvemos o texto cru; se vazio,
// usamos um fallback por status. Nunca expomos o JSON bruto ao usuario.
async function extractErrorMessage(res: Response): Promise<string> {
  const fallback =
    STATUS_FALLBACK[res.status] ?? res.statusText ?? `Erro ${res.status}`;
  let raw: string;
  try {
    raw = await res.text();
  } catch {
    return fallback;
  }
  const trimmed = raw.trim();
  if (!trimmed) return fallback;
  try {
    const parsed = JSON.parse(trimmed) as Record<string, unknown>;
    const field = parsed.message ?? parsed.error ?? parsed.detail ?? parsed.title;
    if (typeof field === 'string' && field.trim()) {
      // Anexa erros de validacao de campo, se houver (VALIDATION_ERROR).
      const extra = Array.isArray(parsed.errors)
        ? parsed.errors.filter((e): e is string => typeof e === 'string')
        : [];
      return extra.length ? `${field.trim()}\n${extra.join('\n')}` : field.trim();
    }
    // JSON valido mas sem campo de mensagem util -> fallback por status.
    return fallback;
  } catch {
    // Nao e JSON (ex.: erro de proxy/gateway em texto) -> devolve o texto cru.
    return trimmed;
  }
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
