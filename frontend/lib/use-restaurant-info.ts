'use client'

import { useEffect, useState } from 'react'
import { api } from './api'

interface TenantConfig {
  restaurantName: string | null
  logoUrl: string | null
  autoAcceptOrders: boolean
}

interface RestaurantInfo {
  restaurantName: string | null
  /** URL da logo da empresa — null enquanto carrega ou se nao configurado */
  logoUrl: string | null
}

export function useRestaurantInfo(): RestaurantInfo {
  const [info, setInfo] = useState<RestaurantInfo>({
    restaurantName: null,
    logoUrl: null,
  })

  useEffect(() => {
    api
      .get<TenantConfig>('/config')
      .then((cfg) =>
        setInfo({
          restaurantName: cfg.restaurantName ?? null,
          logoUrl: cfg.logoUrl ?? null,
        })
      )
      .catch(() => {})
  }, [])

  return info
}
