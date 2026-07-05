// Barra fina de status de conexão — exibida quando não está ao vivo.
// Status nunca só por cor: usa ícone textual + texto descritivo.

import React from 'react';
import { Text, View } from 'react-native';
import { semantic } from '@/theme/colors';
import type { FeedStatus } from '@/lib/ws';

interface StatusBannerProps {
  feedStatus: FeedStatus;
  /** Texto do modo polling (default: intervalo do KDS). */
  pollingLabel?: string;
}

const BANNER_CONFIG: Partial<
  Record<FeedStatus, { icon: string; label: string; bg: string; fg: string }>
> = {
  live: {
    icon: '●',
    label: 'Ao vivo',
    bg: semantic.success.DEFAULT,
    fg: '#ffffff',
  },
  reconnecting: {
    icon: '◌',
    label: 'Reconectando...',
    bg: semantic.warning.DEFAULT,
    fg: semantic.warning.dark,
  },
  polling: {
    icon: '↻',
    label: 'Atualizando a cada 10s',
    bg: '#f97316', // laranja
    fg: '#ffffff',
  },
};

export function StatusBanner({ feedStatus, pollingLabel }: StatusBannerProps) {
  // Não exibir enquanto conectando (estado transitório curto)
  if (feedStatus === 'connecting') return null;

  const config = BANNER_CONFIG[feedStatus];
  if (!config) return null;

  const label =
    feedStatus === 'polling' && pollingLabel ? pollingLabel : config.label;

  return (
    <View
      accessibilityRole="alert"
      accessibilityLiveRegion="polite"
      accessibilityLabel={label}
      style={{
        backgroundColor: config.bg,
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 6,
        paddingVertical: 5,
        paddingHorizontal: 12,
      }}
    >
      <Text
        accessibilityElementsHidden
        importantForAccessibility="no"
        style={{ fontSize: 11, color: config.fg }}
      >
        {config.icon}
      </Text>
      <Text style={{ fontSize: 12, fontWeight: '600', color: config.fg }}>
        {label}
      </Text>
    </View>
  );
}
