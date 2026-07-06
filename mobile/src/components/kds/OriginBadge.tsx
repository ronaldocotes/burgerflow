// Badge de canal de origem do pedido — paridade com o OriginBadge do KDS web.
// OWN (plataforma própria) não renderiza nada; canal desconhecido idem.

import React from 'react';
import { Text, View } from 'react-native';
import { origin as originColors } from '@/theme/colors';
import type { ExternalOrigin } from '@/types/kds';

const CONFIG: Partial<
  Record<ExternalOrigin, { label: string; bg: string; fg: string }>
> = {
  IFOOD: { label: 'iFood', ...originColors.ifood },
  RAPPI: { label: 'Rappi', ...originColors.rappi },
  NINETY_NINE: { label: '99Food', ...originColors.ninetyNine },
};

interface OriginBadgeProps {
  origin?: ExternalOrigin;
}

export function OriginBadge({ origin }: OriginBadgeProps) {
  if (!origin || origin === 'OWN') return null;
  const cfg = CONFIG[origin];
  if (!cfg) return null;
  return (
    <View
      accessibilityLabel={`Pedido via ${cfg.label}`}
      style={{
        backgroundColor: cfg.bg,
        borderRadius: 999,
        paddingHorizontal: 8,
        paddingVertical: 2,
        alignSelf: 'center',
      }}
    >
      <Text style={{ fontSize: 12, fontWeight: '700', color: cfg.fg }}>
        {cfg.label}
      </Text>
    </View>
  );
}
