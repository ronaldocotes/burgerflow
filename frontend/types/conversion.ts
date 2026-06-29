// Tipos para Conversoes (Meta CAPI + Google Data Manager) — Fase 3.7

export type ConversionPlatform = 'META' | 'GOOGLE'
export type ConversionStatus = 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED'

export interface ConversionDispatchResponse {
  id: string
  orderId: string
  platform: ConversionPlatform
  status: ConversionStatus
  eventId: string | null
  responseCode: number | null
  attempts: number
  lastAttemptAt: string | null
  sentAt: string | null
  createdAt: string
}

export type ConversionDispatchPage = {
  content: ConversionDispatchResponse[]
  totalElements: number
  totalPages: number
  number: number
}

export interface ConversionConfigResponse {
  conversionTrackingEnabled: boolean
  hasMetaToken: boolean
  metaPixelId: string | null
  metaTestEventCode: string | null
  googleSgtmUrl: string | null
  googleMeasurementId: string | null
}

export interface ConversionConfigPatchRequest {
  conversionTrackingEnabled?: boolean
  metaPixelId?: string | null
  metaAccessToken?: string | null
  metaTestEventCode?: string | null
  googleSgtmUrl?: string | null
  googleMeasurementId?: string | null
}
