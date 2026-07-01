'use client'

// Integrações — placeholder da Fase 2 (saúde de iFood, OpenDelivery, WAHA, LiteLLM)

import { Plug, Bike, Network, MessageCircle, Cpu, type LucideIcon } from 'lucide-react'
import { useSuperAdminGuard } from '@/lib/use-super-admin-guard'

const INTEGRATIONS: { name: string; description: string; icon: LucideIcon }[] = [
  { name: 'iFood',        description: 'Pedidos e status do marketplace',        icon: Bike },
  { name: 'OpenDelivery', description: '99Food, Rappi e outros via padrão ABRASEL', icon: Network },
  { name: 'WAHA',         description: 'Bot e notificações de WhatsApp',          icon: MessageCircle },
  { name: 'LiteLLM',      description: 'Gateway de IA (copiloto e bot)',          icon: Cpu },
]

export default function IntegracoesPage() {
  useSuperAdminGuard()

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-6">
      <div className="mb-8 flex flex-col items-center gap-2 rounded-xl border border-dashed border-border-medium bg-bg-primary px-4 py-10 text-center">
        <Plug className="h-10 w-10 text-text-muted" aria-hidden="true" />
        <h1 className="text-xl font-bold text-text-primary">Integrações</h1>
        <p className="max-w-md text-sm text-text-secondary">
          Disponível na Fase 2 — saúde de iFood, OpenDelivery, WAHA e LiteLLM em tempo real,
          com semáforo por empresa.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {INTEGRATIONS.map(({ name, description, icon: Icon }) => (
          <div key={name} className="rounded-xl border border-border-light bg-bg-primary p-4">
            <div className="flex items-start justify-between gap-2">
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-bg-tertiary text-text-secondary">
                  <Icon className="h-5 w-5" aria-hidden="true" />
                </div>
                <div>
                  <p className="font-semibold text-text-primary">{name}</p>
                  <p className="text-sm text-text-secondary">{description}</p>
                </div>
              </div>
              <span className="inline-flex shrink-0 items-center rounded-full bg-bg-tertiary px-2.5 py-0.5 text-xs font-semibold text-text-secondary">
                Em breve
              </span>
            </div>
          </div>
        ))}
      </div>
    </main>
  )
}
