'use client'

// Guard client-side do painel /plataforma: redireciona para /login
// se o JWT não tiver role SUPER_ADMIN. Segue o padrão do projeto
// (decode inline base64url do payload, sem Context/middleware).

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { getToken } from '@/lib/auth'

function getRoleFromToken(): string | null {
  try {
    const token = getToken()
    if (!token) return null
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(b64)) as { role?: string; roles?: string[] }
    return payload.role ?? ((Array.isArray(payload.roles) && payload.roles[0]) || null)
  } catch {
    return null
  }
}

export function useSuperAdminGuard() {
  const router = useRouter()
  useEffect(() => {
    if (getRoleFromToken() !== 'SUPER_ADMIN') {
      router.replace('/login')
    }
  }, [router])
}
