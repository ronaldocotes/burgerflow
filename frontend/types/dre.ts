// Tipos para o módulo DRE (Demonstrativo de Resultado do Exercício) do MenuFlow.

export type DrePeriod = 'today' | 'week' | 'month' | 'custom'

export type ExpenseCategory = 'RENT' | 'UTILITIES' | 'PAYROLL' | 'MARKETING' | 'OTHER'

export interface DreResponse {
  periodStart: string
  periodEnd: string
  grossRevenueCents: number
  marketplaceFeesCents: number
  cardFeesCents: number
  taxCents: number
  netRevenueCents: number
  cogsCents: number
  grossProfitCents: number
  operatingExpensesCents: number
  netProfitCents: number
  orderCount: number
  averageTicketCents: number
  grossMarginPct: number   // ex: 41.39 (já em %)
  netMarginPct: number
  ordersByChannel: Record<string, number>
  ordersByPaymentMethod: Record<string, number>
}

export interface OperatingExpenseRequest {
  description: string
  amountCents: number
  category: ExpenseCategory
  expenseDate: string // "YYYY-MM-DD"
}

export interface OperatingExpenseResponse {
  id: string
  description: string
  amountCents: number
  category: string
  expenseDate: string
  createdAt: string
  updatedAt: string
}
