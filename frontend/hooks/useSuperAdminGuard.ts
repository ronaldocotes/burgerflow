'use client'

// Guard client-side do painel /plataforma: decodifica o JWT do localStorage
// e redireciona para /login se a role nao for SUPER_ADMIN. Retorna { loading }
// para o layout segurar a renderizacao ate a checagem concluir (evita flash
// de conteudo para quem nao tem acesso). Decode inline base64url do payload,
// sem Context/middleware — mesmo padrao do Topbar.

import { useEffect, useState } from 'react'
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

export function useSuperAdminGuard(): { loading: boolean } {
  const router = useRouter()
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (getRoleFromToken() !== 'SUPER_ADMIN') {
      router.replace('/login')
    } else {
      setLoading(false)
    }
  }, [router])

  return { loading }
}
