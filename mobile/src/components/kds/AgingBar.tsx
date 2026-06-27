// Barra colorida h=4 no topo do card — padrão kohli.design.
// Verde → âmbar → vermelho por tempo decorrido. Mais legível em cozinha com luz forte.

import React from 'react';
import { View } from 'react-native';
import { semantic } from '@/theme/colors';
import type { KdsOrder } from '@/types/kds';

function agingColor(order: KdsOrder, now: number): string {
  const elapsed = (now - new Date(order.createdAt).getTime()) / 60_000;
  const limit = order.estimatedPrepTimeMinutes ?? 15;
  if (elapsed >= limit) return semantic.error.DEFAULT;
  if (elapsed >= limit * 0.75) return semantic.warning.DEFAULT;
  return semantic.success.DEFAULT;
}

interface AgingBarProps {
  order: KdsOrder;
  now: number;
}

export function AgingBar({ order, now }: AgingBarProps) {
  return (
    <View
      style={{ height: 4, backgroundColor: agingColor(order, now) }}
      accessibilityElementsHidden
      importantForAccessibility="no"
    />
  );
}
