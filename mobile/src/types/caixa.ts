// Tipos do dominio de Caixa (CashSession) — fase M4. Espelham os DTOs reais do
// backend (CashSessionDtos.kt). Dinheiro SEMPRE em CENTAVOS (Long no backend).

export type CashSessionStatus = 'OPEN' | 'CLOSED';
export type CashEntryType = 'WITHDRAWAL' | 'DEPOSIT';
export type ReconciliationMethod = 'CASH' | 'CARD' | 'PIX' | 'OTHER';

export interface CashEntry {
  id: string;
  type: CashEntryType;
  amountCents: number;
  reason: string | null;
  createdByUserId: string;
  createdAt: string;
}

export interface PaymentMethodReconciliation {
  method: ReconciliationMethod;
  expectedCents: number;
  countedCents: number | null;
  differenceCents: number | null;
}

export interface CashSession {
  id: string;
  status: CashSessionStatus;
  openedByUserId: string;
  openedAt: string;
  openingAmountCents: number;
  closedByUserId: string | null;
  closedAt: string | null;
  cashSalesCents: number;
  depositsCents: number;
  withdrawalsCents: number;
  expectedCents: number;
  countedCents: number | null;
  differenceCents: number | null;
  reconciliation: PaymentMethodReconciliation[];
  withdrawnAtCloseCents: number | null;
  suggestedNextOpeningCents: number | null;
  entries: CashEntry[];
  notes: string | null;
}

export interface OpenSessionInput {
  openingAmountCents: number;
  confirmZeroOpening?: boolean;
  notes?: string;
}

export interface EntryInput {
  type: CashEntryType;
  amountCents: number;
  reason: string;
}

export interface CloseSessionInput {
  countedAmountCents: number;
  countedCardCents?: number;
  countedPixCents?: number;
  withdrawnAmountCents?: number;
  notes?: string;
}
