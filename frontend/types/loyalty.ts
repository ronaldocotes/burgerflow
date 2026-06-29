// Tipos do programa de fidelidade punch-card (Fase 3.3)

export type LoyaltyTransactionReason =
  | 'ORDER_PAID'
  | 'REWARD_REDEEMED'
  | 'MANUAL_ADJUST'
  | 'EXPIRY'

export interface LoyaltyTransactionResponse {
  id: string
  pointsDelta: number        // positivo = ganhou, negativo = resgatou/ajuste
  reason: LoyaltyTransactionReason
  description?: string
  orderId?: string
  createdAt: string          // ISO 8601
}

export interface LoyaltyStatusResponse {
  loyaltyPoints: number      // saldo atual
  rewardThreshold: number    // pontos para 1 punch
  progress: number           // pontos no punch atual (loyaltyPoints % threshold)
  punches: number            // recompensas desbloqueadas nao resgatadas
  pendingRewardId?: string   // UUID do primeiro punch disponivel (null se punches==0)
  transactions: LoyaltyTransactionResponse[]  // ultimas 10
}

export interface LoyaltyAdjustRequest {
  delta: number              // pode ser negativo
  description: string
}

export interface LoyaltyConfig {
  loyaltyEnabled: boolean
  loyaltyPointsPerReal: number
  loyaltyRewardThreshold: number
  loyaltyRewardDescription?: string
}
