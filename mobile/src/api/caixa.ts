// Camada de API do dominio de Caixa (CashSession) — fase M4.
// Base /api/v1 ja vem em AppConfig.API_URL; os paths aqui sao relativos a
// /cash-sessions. Contrato confirmado em CashSessionController.kt:
// roles ADMIN/MANAGER/CASHIER (validado no servidor); SEM Idempotency-Key.
import { get, post } from '@/lib/api';
import type {
  CashSession,
  CloseSessionInput,
  EntryInput,
  OpenSessionInput,
} from '@/types/caixa';

/**
 * Turno atual: 200 com o turno OPEN, ou 204 (sem corpo) quando o caixa esta
 * fechado. O cliente HTTP devolve undefined no 204 -> aqui null sinaliza
 * "caixa fechado".
 */
export const getCurrentSession = async (): Promise<CashSession | null> => {
  const res = await get<CashSession | undefined>('/cash-sessions/current');
  return res ?? null;
};

export const openSession = (body: OpenSessionInput) =>
  post<CashSession>('/cash-sessions/open', body);

export const addEntry = (sessionId: string, body: EntryInput) =>
  post<CashSession>(`/cash-sessions/${sessionId}/entries`, body);

export const closeSession = (sessionId: string, body: CloseSessionInput) =>
  post<CashSession>(`/cash-sessions/${sessionId}/close`, body);
