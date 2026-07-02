'use client'

import { useState } from 'react'
import { usePathname } from 'next/navigation'
import { Sidebar } from './Sidebar'
import { Topbar } from './Topbar'
import { CopilotChat } from '@/components/ai/CopilotChat'

// Rotas publicas OU com layout proprio (/plataforma): renderiza children direto
const PUBLIC_PREFIXES = ['/', '/login', '/cardapio', '/aceitar-convite', '/acompanhar', '/motoboy', '/plataforma']

function isPublicRoute(pathname: string): boolean {
  if (pathname === '/') return true
  return PUBLIC_PREFIXES.slice(1).some(
    (prefix) => pathname === prefix || pathname.startsWith(prefix + '/')
  )
}

export function ClientLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()
  const [mobileOpen, setMobileOpen] = useState(false)

  if (isPublicRoute(pathname)) {
    return <>{children}</>
  }

  return (
    <div className="flex h-screen overflow-hidden bg-bg-secondary">
      <Sidebar mobileOpen={mobileOpen} onClose={() => setMobileOpen(false)} />
      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        <Topbar onMenuClick={() => setMobileOpen(true)} />
        <div className="flex-1 overflow-auto">
          {children}
        </div>
      </div>
      <CopilotChat />
    </div>
  )
}
