// Tela principal do KDS — 3 colunas landscape (PENDING / PREPARING / READY).
// Persona: João/Cozinheiro — clareza > design; cartões GRANDES; 1 toque para avançar.
// Atualização: STOMP + polling 10s de fallback + AppState reconexão.

import React, { useCallback, useState } from 'react';
import { Alert, SafeAreaView, View } from 'react-native';
import { useKdsFeed } from '@/hooks/useKdsFeed';
import { KdsColumn } from '@/components/kds/KdsColumn';
import { StatusBanner } from '@/components/ui/StatusBanner';
import { api, ApiError } from '@/lib/api';
import { kds, theme } from '@/theme/colors';
import type { KdsOrder, OrderStatus } from '@/types/kds';

const NEXT_STATUS: Partial<Record<OrderStatus, OrderStatus>> = {
  PENDING: 'PREPARING',
  PREPARING: 'READY',
  READY: 'DELIVERED',
};

export default function KdsScreen() {
  const { orders, feedStatus, now, refresh } = useKdsFeed();
  const [localOrders, setLocalOrders] = useState<KdsOrder[] | null>(null);

  // Usa localOrders (atualização otimista) quando disponível; senão usa os do feed
  const displayOrders = localOrders ?? orders;

  // ── Avançar status (otimista) ─────────────────────────────────────────────
  const handleAdvance = useCallback(
    async (order: KdsOrder) => {
      const next = NEXT_STATUS[order.status];
      if (!next) return;

      // Atualização otimista: remove do board se DELIVERED
      const prevOrders = localOrders ?? orders;
      const isTerminal = next === 'DELIVERED';
      const optimistic = isTerminal
        ? prevOrders.filter((o) => o.orderId !== order.orderId)
        : prevOrders.map((o) =>
            o.orderId === order.orderId ? { ...o, status: next } : o,
          );
      setLocalOrders(optimistic);

      try {
        await api.put(`/orders/${order.orderId}/status`, { status: next });
        // STOMP vai reconciliar; limpa estado local para deixar o feed assumir
        setLocalOrders(null);
      } catch (e) {
        // Reverte atualização otimista
        setLocalOrders(null);
        const msg = e instanceof ApiError ? e.message : 'Erro ao avançar pedido.';
        Alert.alert('Erro', msg, [
          { text: 'Tentar de novo', onPress: () => handleAdvance(order) },
          { text: 'Cancelar', style: 'cancel', onPress: () => refresh() },
        ]);
      }
    },
    [localOrders, orders, refresh],
  );

  // ── Cancelar pedido ───────────────────────────────────────────────────────
  const handleCancel = useCallback(
    (order: KdsOrder) => {
      Alert.prompt(
        'Motivo do cancelamento',
        `Pedido #${order.orderNumber} — informe o motivo (obrigatório):`,
        [
          { text: 'Voltar', style: 'cancel' },
          {
            text: 'Cancelar pedido',
            style: 'destructive',
            onPress: async (reason?: string) => {
              if (!reason?.trim()) {
                Alert.alert('Motivo obrigatorio', 'Informe o motivo do cancelamento.');
                return;
              }
              try {
                await api.put(`/orders/${order.orderId}/status`, {
                  status: 'CANCELLED',
                  cancellationReason: reason.trim(),
                });
                setLocalOrders((prev) =>
                  (prev ?? orders).filter((o) => o.orderId !== order.orderId),
                );
              } catch (e) {
                const msg = e instanceof ApiError ? e.message : 'Erro ao cancelar.';
                Alert.alert('Erro', msg);
                refresh();
              }
            },
          },
        ],
        'plain-text',
      );
    },
    [orders, refresh],
  );

  const pending = displayOrders.filter((o) => o.status === 'PENDING');
  const preparing = displayOrders.filter((o) => o.status === 'PREPARING');
  const ready = displayOrders.filter((o) => o.status === 'READY');

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.bg.primary }}>
      {/* Banner de status de conexao */}
      <StatusBanner feedStatus={feedStatus} />

      {/* Board: 3 colunas landscape */}
      <View style={{ flex: 1, flexDirection: 'row', gap: 6, padding: 8 }}>
        <KdsColumn
          title="Novos"
          color={kds.pending}
          orders={pending}
          now={now}
          onAdvance={handleAdvance}
          onCancel={handleCancel}
        />
        <KdsColumn
          title="Preparando"
          color={kds.preparing}
          orders={preparing}
          now={now}
          onAdvance={handleAdvance}
          onCancel={handleCancel}
        />
        <KdsColumn
          title="Prontos"
          color={kds.ready}
          orders={ready}
          now={now}
          onAdvance={handleAdvance}
          onCancel={handleCancel}
        />
      </View>
    </SafeAreaView>
  );
}
