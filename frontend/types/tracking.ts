// Tipos para Rastreamento first-party (Fase 3.6)

export interface TrackingLinkResponse {
  id: string
  slug: string
  name: string
  source: string
  medium: string | null
  campaign: string | null
  destinationUrl: string
  active: boolean
  clickCount: number
  createdAt: string
  shareUrl: string
}

export interface TrackingLinkCreateRequest {
  name: string
  source: string
  medium?: string
  campaign?: string
}

export interface TrackingSummaryResponse {
  trackingLinkId: string
  name: string
  source: string
  slug: string
  clicks: number
  conversions: number
  revenueCents: number
  conversionRate: number
}

export type TrackingLinksPage = {
  content: TrackingLinkResponse[]
  totalElements: number
  totalPages: number
  number: number
}
