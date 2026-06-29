// Tipos para Carrinhos Abandonados (Fase 3.5)

export type CartSessionStatus = 'ACTIVE' | 'SENT' | 'RECOVERED' | 'EXPIRED'

export interface CartSessionResponse {
  id: string
  orderId: string
  customerPhone: string | null
  totalCents: number
  status: CartSessionStatus
  recoveryMessageSentAt: string | null
  recoveredAt: string | null
  expiredAt: string | null
  createdAt: string
}

export interface CartRecoveryConfig {
  cartRecoveryEnabled: boolean
  cartRecoveryDelayMinutes: number
  cartRecoveryMessage: string
  cartRecoveryExpiryHours: number
}

export interface CartSessionPage {
  content: CartSessionResponse[]
  totalElements: number
  totalPages: number
  number: number
}
