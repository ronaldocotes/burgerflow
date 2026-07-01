'use client'

// Uso de IA — placeholder da Fase 3 (consumo e custo estimado por empresa)

import { BrainCircuit } from 'lucide-react'
import { useSuperAdminGuard } from '@/lib/use-super-admin-guard'

export default function UsoIaPage() {
  useSuperAdminGuard()

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-6">
      <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-border-medium bg-bg-primary px-4 py-14 text-center">
        <BrainCircuit className="h-10 w-10 text-text-muted" aria-hidden="true" />
        <h1 className="text-xl font-bold text-text-primary">Uso de IA</h1>
        <p className="max-w-md text-sm text-text-secondary">
          Uso de IA e custo estimado por empresa. Disponível na Fase 3.
        </p>
        <span className="mt-2 inline-flex items-center rounded-full bg-bg-tertiary px-3 py-1 text-xs font-semibold text-text-secondary">
          Em breve
        </span>
      </div>
    </main>
  )
}
