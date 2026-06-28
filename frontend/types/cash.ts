// Tipos do módulo de turno de caixa (Fase 2.1)

export interface CashEntryResponse {
  id: string;
  type: "WITHDRAWAL" | "DEPOSIT";
  amountCents: number;
  reason: string;
  createdByUserId: string;
  createdAt: string;
}

export interface CashSessionResponse {
  id: string;
  status: "OPEN" | "CLOSED";
  openedByUserId: string;
  openedAt: string;
  openingAmountCents: number;
  closedByUserId?: string;
  closedAt?: string;
  cashSalesCents: number;
  depositsCents: number;
  withdrawalsCents: number;
  expectedCents: number;
  countedCents?: number;
  differenceCents?: number;
  entries: CashEntryResponse[];
  notes?: string;
}

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number; // página atual (0-based)
  size: number;
  last: boolean;
}
