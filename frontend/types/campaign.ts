// Tipos para Campanhas WhatsApp e RFV (Fase 3.4)

export type CampaignSegment =
  | 'RFV_INACTIVE'
  | 'RFV_AT_RISK'
  | 'RFV_LOYAL'
  | 'ALL_OPT_IN'
  | 'CUSTOM'

export type CampaignStatus =
  | 'DRAFT'
  | 'RUNNING'
  | 'PAUSED'
  | 'COMPLETED'
  | 'FAILED'

export type CampaignSendStatus =
  | 'QUEUED'
  | 'SENT'
  | 'FAILED'
  | 'OPT_OUT'

export type RfvSegment =
  | 'LOYAL'
  | 'AT_RISK'
  | 'INACTIVE'
  | 'NEW'

export interface CampaignCreateRequest {
  name: string
  messageTemplate: string
  segment: CampaignSegment
  segmentParams?: string
  scheduledAt?: string
}

export interface CampaignResponse {
  id: string
  name: string
  messageTemplate: string
  segment: CampaignSegment
  segmentParams?: string
  status: CampaignStatus
  scheduledAt?: string
  startedAt?: string
  completedAt?: string
  totalRecipients: number
  sentCount: number
  failedCount: number
  createdAt: string
  updatedAt: string
}

export interface CampaignSendResponse {
  id: string
  customerId: string
  phone: string
  status: CampaignSendStatus
  sentAt?: string
  errorMessage?: string
  createdAt: string
}

export interface RfvScoreResponse {
  customerId: string
  customerName: string | null
  recencyDays: number
  frequency: number
  monetaryValue: number
  segment: RfvSegment
}

export type CampaignPage<T> = {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
}
