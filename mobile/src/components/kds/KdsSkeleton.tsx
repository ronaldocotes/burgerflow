// Skeleton de carregamento do board do KDS (estado loading).
// Blocos neutros estáticos — sem animação de shimmer para poupar o tablet.

import React from 'react';
import { View } from 'react-native';
import { theme } from '@/theme/colors';

function SkeletonCard() {
  return (
    <View
      style={{
        borderRadius: 12,
        backgroundColor: theme.bg.primary,
        marginHorizontal: 6,
        marginVertical: 5,
        padding: 12,
        gap: 8,
      }}
    >
      <View style={{ height: 22, width: '40%', borderRadius: 6, backgroundColor: theme.bg.tertiary }} />
      <View style={{ height: 14, width: '65%', borderRadius: 6, backgroundColor: theme.bg.tertiary }} />
      <View style={{ height: 14, width: '80%', borderRadius: 6, backgroundColor: theme.bg.tertiary }} />
      <View style={{ height: 40, borderRadius: 6, backgroundColor: theme.bg.tertiary }} />
    </View>
  );
}

interface KdsSkeletonProps {
  /** Quantas colunas desenhar (3 em landscape, 1 em portrait). */
  columns: number;
}

export function KdsSkeleton({ columns }: KdsSkeletonProps) {
  return (
    <View
      accessibilityLabel="Carregando pedidos"
      style={{ flex: 1, flexDirection: 'row', gap: 6, padding: 8 }}
    >
      {Array.from({ length: columns }).map((_, c) => (
        <View
          key={c}
          style={{
            flex: 1,
            backgroundColor: theme.bg.secondary,
            borderRadius: 10,
            paddingTop: 6,
          }}
        >
          <SkeletonCard />
          <SkeletonCard />
        </View>
      ))}
    </View>
  );
}
