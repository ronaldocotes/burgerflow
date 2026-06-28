'use client'

import { useEffect, useState } from 'react'
import { api } from './api'

interface TenantConfig {
  restaurantName: string | null
  autoAcceptOrders: boolean
}

export function useRestaurantInfo() {
  const [restaurantName, setRestaurantName] = useState<string | null>(null)

  useEffect(() => {
    api
      .get<TenantConfig>('/config')
      .then((cfg) => setRestaurantName(cfg.restaurantName ?? null))
      .catch(() => {})
  }, [])

  return { restaurantName }
}
