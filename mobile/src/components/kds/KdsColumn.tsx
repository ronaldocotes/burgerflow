// Coluna do board KDS: cabeçalho colorido + lista de cards.
// Ordenados por createdAt ASC (mais antigo no topo — João precisa ver o que está atrasando).

import React, { useCallback } from 'react';
import { FlatList, Text, View } from 'react-native';
import { KdsCard } from './KdsCard';
import { theme } from '@/theme/colors';
import type { KdsOrder } from '@/types/kds';

interface KdsColumnProps {
  title: string;
  color: string;
  orders: KdsOrder[];
  now: number;
  onAdvance: (order: KdsOrder) => Promise<void>;
  onCancel: (order: KdsOrder) => void;
}

export function KdsColumn({ title, color, orders, now, onAdvance, onCancel }: KdsColumnProps) {
  // Ordena mais antigo no topo — prioridade de atenção do cozinheiro
  const sorted = [...orders].sort(
    (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime(),
  );

  const renderItem = useCallback(
    ({ item }: { item: KdsOrder }) => (
      <KdsCard order={item} now={now} onAdvance={onAdvance} onCancel={onCancel} />
    ),
    [now, onAdvance, onCancel],
  );

  const keyExtractor = useCallback((item: KdsOrder) => item.orderId, []);

  return (
    <View style={{ flex: 1, flexDirection: 'column' }}>
      {/* Cabeçalho da coluna */}
      <View
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          gap: 8,
          backgroundColor: color,
          paddingHorizontal: 14,
          paddingVertical: 10,
          borderTopLeftRadius: 10,
          borderTopRightRadius: 10,
        }}
      >
        <Text style={{ fontSize: 14, fontWeight: '700', color: '#ffffff', flex: 1 }}>
          {title}
        </Text>
        {/* Badge de contagem */}
        <View
          style={{
            backgroundColor: 'rgba(255,255,255,0.25)',
            borderRadius: 99,
            paddingHorizontal: 8,
            paddingVertical: 2,
          }}
        >
          <Text style={{ fontSize: 13, fontWeight: '700', color: '#ffffff' }}>
            {orders.length}
          </Text>
        </View>
      </View>

      {/* Lista de pedidos */}
      <FlatList
        data={sorted}
        renderItem={renderItem}
        keyExtractor={keyExtractor}
        style={{ flex: 1, backgroundColor: theme.bg.secondary }}
        contentContainerStyle={{ paddingVertical: 6, flexGrow: 1 }}
        ListEmptyComponent={
          <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', paddingTop: 40 }}>
            <Text style={{ fontSize: 14, color: theme.text.muted }}>Nenhum pedido</Text>
          </View>
        }
        removeClippedSubviews
        initialNumToRender={8}
      />
    </View>
  );
}
