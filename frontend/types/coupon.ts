// Tipos de Cupons & Descontos — MenuFlow

export type DiscountType = 'FIXED' | 'PERCENT'

export interface CouponCreateRequest {
  code: string
  description?: string
  discountType: DiscountType
  /** centavos quando FIXED; percent x100 quando PERCENT (ex: 15% = 1500) */
  discountValue: number
  minOrderCents: number
  maxUses?: number
  maxUsesPerCustomer: number
  validFrom: string   // ISO 8601 instant
  validUntil: string  // ISO 8601 instant
  active: boolean
}

export interface CouponResponse {
  id: string
  code: string
  description?: string
  discountType: DiscountType
  discountValue: number
  minOrderCents: number
  maxUses?: number
  maxUsesPerCustomer: number
  validFrom: string
  validUntil: string
  active: boolean
  createdAt: string
  updatedAt: string
}

export interface CouponRedemptionResponse {
  id: string
  couponId: string
  orderId: string
  customerPhone?: string
  discountAppliedCents: number
  redeemedAt: string
}

export interface ApplyCouponRequest {
  code: string
  subtotalCents: number
  customerPhone?: string
}

export interface ApplyCouponResponse {
  valid: boolean
  discountCents: number
  description?: string
}
