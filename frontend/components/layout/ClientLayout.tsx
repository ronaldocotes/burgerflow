'use client'

import { usePathname } from 'next/navigation'
import { Sidebar } from './Sidebar'

// Rotas publicas: sem sidebar, renderiza children direto
const PUBLIC_PREFIXES = ['/', '/login', '/cardapio']

function isPublicRoute(pathname: string): boolean {
  if (pathname === '/') return true
  return PUBLIC_PREFIXES.slice(1).some(
    (prefix) => pathname === prefix || pathname.startsWith(prefix + '/')
  )
}

export function ClientLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname()

  if (isPublicRoute(pathname)) {
    return <>{children}</>
  }

  return (
    <div className="flex h-screen overflow-hidden bg-bg-secondary">
      <Sidebar />
      <div className="flex-1 overflow-auto">
        {children}
      </div>
    </div>
  )
}
