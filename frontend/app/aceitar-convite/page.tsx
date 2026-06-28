import { Suspense } from 'react'
import { UtensilsCrossed } from 'lucide-react'
import { AceitarConviteContent } from './AceitarConviteContent'

function LoadingFallback() {
  return (
    <div className="space-y-4">
      {Array.from({ length: 4 }).map((_, i) => (
        <div key={i} className="h-10 animate-pulse rounded-lg bg-bg-tertiary" />
      ))}
    </div>
  )
}

export default function AceitarConvitePage() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-bg-secondary px-6 py-12">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex justify-center">
          <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-primary-700 text-white shadow-card">
            <UtensilsCrossed className="h-7 w-7" aria-hidden="true" />
          </div>
        </div>
        <Suspense fallback={<LoadingFallback />}>
          <AceitarConviteContent />
        </Suspense>
        <p className="mt-8 text-center text-xs text-text-muted">
          MenuFlow · Sistema de Gestao · v1.0 · 2026
        </p>
      </div>
    </div>
  )
}
