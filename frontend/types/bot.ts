export interface BotHandoffResponse {
  id: string
  customerPhone: string
  lastBotMessage: string | null
  resolved: boolean
  createdAt: string
  resolvedAt: string | null
}

export interface BotHandoffPage {
  content: BotHandoffResponse[]
  totalElements: number
  totalPages: number
  number: number
}

export interface BotConfig {
  botEnabled: boolean
  botSystemPrompt: string | null
  botHandoffKeyword: string
  botWelcomeMessage: string | null
  botHandoffMessage: string | null
  openingHoursMonday: string | null
  openingHoursTuesday: string | null
  openingHoursWednesday: string | null
  openingHoursThursday: string | null
  openingHoursFriday: string | null
  openingHoursSaturday: string | null
  openingHoursSunday: string | null
}
