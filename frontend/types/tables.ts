// Tipos da tela /mesas espelhando TableDto / TableSessionDto do backend.

export type SessionStatus = "OPEN" | "BILLING" | "CLOSED";

export interface TableSession {
  sessionId: string;
  status: SessionStatus;
  openedAt: string;
  billRequestedAt?: string | null;
}

export interface TableDto {
  id: string;
  label: string;
  seats: number;
  sortOrder: number;
  active: boolean;
  session?: TableSession | null;
}

export type FeedStatus = "connecting" | "live" | "reconnecting" | "polling";
