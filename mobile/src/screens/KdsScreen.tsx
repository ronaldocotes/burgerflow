// Tela principal do KDS — board da cozinha (Fase M1).
// Landscape (tablet na cozinha): 3 colunas PENDING / PREPARING / READY.
// Portrait: abas segmentadas por status, uma coluna por vez (evita colunas
// espremidas em celular). Persona: João/Cozinheiro — clareza > design.
// Atualização: STOMP /topic/kds/{tenant} + polling 10s de fallback + AppState.

import React, { useCallback, useMemo, useState } from 'react';
import {
  Alert,
  Pressable,
  SafeAreaView,
  Text,
  View,
  useWindowDimensions,
} from 'react-native';
import { useKdsFeed } from '@/hooks/useKdsFeed';
import { KdsColumn } from '@/components/kds/KdsColumn';
import { KdsSkeleton } from '@/components/kds/KdsSkeleton';
import { CancelModal } from '@/components/kds/CancelModal';
import { StatusBanner } from '@/components/ui/StatusBanner';
import { api, ApiError } from '@/lib/api';
import { kds, theme } from '@/theme/colors';
import type { KdsOrder, OrderStatus } from '@/types/kds';

const NEXT_STATUS: Partial<Record<OrderStatus, OrderStatus>> = {
  PENDING: 'PREPARING',
  PREPARING: 'READY',
  READY: 'DELIVERED',
};

type BoardStatus = 'PENDING' | 'PREPARING' | 'READY';

const COLUMNS: { status: BoardStatus; title: string; color: string }[] = [
  { status: 'PENDING', title: 'Novos', color: kds.pending },
  { status: 'PREPARING', title: 'Preparando', color: kds.preparing },
  { status: 'READY', title: 'Prontos', color: kds.ready },
];

// ── Estado de erro (sem dados) ────────────────────────────────────────────────

function ErrorView({ onRetry }: { onRetry: () => void }) {
  return (
    <View style={{ flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24, gap: 12 }}>
      <Text style={{ fontSize: 18, fontWeight: '700', color: theme.text.primary, textAlign: 'center' }}>
        Não foi possível carregar os pedidos
      </Text>
      <Text style={{ fontSize: 14, color: theme.text.secondary, textAlign: 'center' }}>
        Verifique a conexão com a internet e tente novamente.
      </Text>
      <Pressable
        onPress={onRetry}
        accessibilityRole="button"
        accessibilityLabel="Tentar carregar os pedidos de novo"
        style={({ pressed }) => ({
          minHeight: 48,
          paddingHorizontal: 24,
          borderRadius: 12,
          backgroundColor: theme.brand,
          alignItems: 'center',
          justifyContent: 'center',
          opacity: pressed ? 0.85 : 1,
        })}
      >
        <Text style={{ fontSize: 15, fontWeight: '700', color: theme.text.onBrand }}>
          Tentar de novo
        </Text>
      </Pressable>
    </View>
  );
}

// ── Tela ─────────────────────────────────────────────────────────────────────

export default function KdsScreen() {
  const { orders, feedStatus, now, loading, error, refresh } = useKdsFeed();
  const [localOrders, setLocalOrders] = useState<KdsOrder[] | null>(null);
  const [cancelTarget, setCancelTarget] = useState<KdsOrder | null>(null);
  const [activeTab, setActiveTab] = useState<BoardStatus>('PENDING');
  const { width, height } = useWindowDimensions();
  const isLandscape = width > height;

  // Usa localOrders (atualização otimista) quando disponível; senão usa os do feed
  const displayOrders = localOrders ?? orders;

  const byStatus = useMemo(() => {
    const map: Record<BoardStatus, KdsOrder[]> = {
      PENDING: [],
      PREPARING: [],
      READY: [],
    };
    for (const o of displayOrders) {
      if (o.status === 'PENDING' || o.status === 'PREPARING' || o.status === 'READY') {
        map[o.status].push(o);
      }
    }
    return map;
  }, [displayOrders]);

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

  // ── Cancelar pedido (motivo obrigatório via CancelModal) ─────────────────
  // Backend: PUT /orders/{id}/status { status: CANCELLED, reason } — mesmo
  // contrato do KDS web (OrderStatusUpdateRequest.reason).
  const handleCancelConfirm = useCallback(
    async (reason: string) => {
      if (!cancelTarget) return;
      await api.put(`/orders/${cancelTarget.orderId}/status`, {
        status: 'CANCELLED',
        reason,
      });
      // Remoção otimista + refresh para reconciliar com o servidor.
      setLocalOrders((prev) =>
        (prev ?? orders).filter((o) => o.orderId !== cancelTarget.orderId),
      );
      await refresh();
      setLocalOrders(null);
    },
    [cancelTarget, orders, refresh],
  );

  const activeColumn =
    COLUMNS.find((c) => c.status === activeTab) ?? COLUMNS[0];

  return (
    <SafeAreaView style={{ flex: 1, backgroundColor: theme.bg.primary }}>
      {/* Banner de status de conexao */}
      <StatusBanner feedStatus={feedStatus} />

      {loading ? (
        <KdsSkeleton columns={isLandscape ? 3 : 1} />
      ) : error && displayOrders.length === 0 ? (
        <ErrorView onRetry={() => void refresh()} />
      ) : isLandscape ? (
        // Board landscape: 3 colunas lado a lado
        <View style={{ flex: 1, flexDirection: 'row', gap: 6, padding: 8 }}>
          {COLUMNS.map((c) => (
            <KdsColumn
              key={c.status}
              title={c.title}
              color={c.color}
              orders={byStatus[c.status]}
              now={now}
              onAdvance={handleAdvance}
              onCancel={setCancelTarget}
            />
          ))}
        </View>
      ) : (
        // Portrait: abas segmentadas por status + uma coluna por vez
        <View style={{ flex: 1, padding: 8, gap: 8 }}>
          <View accessibilityRole="tablist" style={{ flexDirection: 'row', gap: 6 }}>
            {COLUMNS.map((c) => {
              const active = activeTab === c.status;
              return (
                <Pressable
                  key={c.status}
                  onPress={() => setActiveTab(c.status)}
                  accessibilityRole="tab"
                  accessibilityState={{ selected: active }}
                  accessibilityLabel={`${c.title}: ${byStatus[c.status].length} pedidos`}
                  style={{
                    flex: 1,
                    minHeight: 48,
                    borderRadius: 10,
                    alignItems: 'center',
                    justifyContent: 'center',
                    backgroundColor: active ? c.color : theme.bg.tertiary,
                  }}
                >
                  <Text
                    style={{
                      fontSize: 14,
                      fontWeight: '700',
                      color: active ? theme.text.onBrand : theme.text.secondary,
                    }}
                  >
                    {c.title} ({byStatus[c.status].length})
                  </Text>
                </Pressable>
              );
            })}
          </View>
          <KdsColumn
            title={activeColumn.title}
            color={activeColumn.color}
            orders={byStatus[activeColumn.status]}
            now={now}
            onAdvance={handleAdvance}
            onCancel={setCancelTarget}
          />
        </View>
      )}

      {/* Modal de cancelamento (motivo obrigatório) */}
      {cancelTarget ? (
        <CancelModal
          order={cancelTarget}
          onClose={() => setCancelTarget(null)}
          onConfirm={handleCancelConfirm}
        />
      ) : null}
    </SafeAreaView>
  );
}
