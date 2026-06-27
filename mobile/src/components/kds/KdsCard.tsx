// Card individual de pedido no board do KDS.
// Todos os botões >= 48dp. Avançar: barra inteira no fundo do card.
// Cancelar: Alert nativo (sem modal — cozinheiro precisa de feedback rápido).

import React, { useEffect, useRef, useState } from 'react';
import { Alert, Pressable, Text, View } from 'react-native';
import { AgingBar } from './AgingBar';
import { kds, semantic, theme } from '@/theme/colors';
import type { KdsOrder, OrderStatus } from '@/types/kds';

// ── Constantes de status ──────────────────────────────────────────────────────

const NEXT_STATUS: Partial<Record<OrderStatus, OrderStatus>> = {
  PENDING: 'PREPARING',
  PREPARING: 'READY',
  READY: 'DELIVERED',
};

const ADVANCE_LABEL: Partial<Record<OrderStatus, string>> = {
  PENDING: 'Iniciar preparo',
  PREPARING: 'Pronto',
  READY: 'Entregue',
};

const ADVANCE_COLOR: Partial<Record<OrderStatus, string>> = {
  PENDING: kds.preparing,
  PREPARING: kds.ready,
  READY: kds.delivered,
};

const ORDER_TYPE_ICON: Record<string, string> = {
  DINE_IN: 'Mesa',
  TAKEAWAY: 'Retirada',
  DELIVERY: 'Entrega',
};

// ── TimerText ─────────────────────────────────────────────────────────────────

function elapsedLabel(createdAt: string, now: number): string {
  const secs = Math.floor((now - new Date(createdAt).getTime()) / 1000);
  const m = Math.floor(secs / 60);
  const s = secs % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

function isOverdue(order: KdsOrder, now: number): boolean {
  const elapsed = (now - new Date(order.createdAt).getTime()) / 60_000;
  const limit = order.estimatedPrepTimeMinutes ?? 15;
  return elapsed >= limit;
}

interface TimerTextProps {
  createdAt: string;
  order: KdsOrder;
}

function TimerText({ createdAt, order }: TimerTextProps) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(t);
  }, []);

  const overdue = isOverdue(order, now);
  return (
    <Text
      accessibilityLabel={`Tempo decorrido: ${elapsedLabel(createdAt, now)}`}
      style={{
        fontVariant: ['tabular-nums'],
        fontSize: overdue ? 16 : 14,
        fontWeight: overdue ? '700' : '500',
        color: overdue ? semantic.error.DEFAULT : theme.text.secondary,
      }}
    >
      {elapsedLabel(createdAt, now)}
    </Text>
  );
}

// ── KdsCard ───────────────────────────────────────────────────────────────────

interface KdsCardProps {
  order: KdsOrder;
  now: number;
  onAdvance: (order: KdsOrder) => Promise<void>;
  onCancel: (order: KdsOrder) => void;
}

export function KdsCard({ order, now, onAdvance, onCancel }: KdsCardProps) {
  const [advancing, setAdvancing] = useState(false);

  const nextStatus = NEXT_STATUS[order.status];
  const advanceLabel = ADVANCE_LABEL[order.status];
  const advanceColor = ADVANCE_COLOR[order.status] ?? kds.ready;

  async function handleAdvance() {
    if (advancing || !nextStatus) return;
    setAdvancing(true);
    try {
      await onAdvance(order);
    } finally {
      setAdvancing(false);
    }
  }

  function handleCancel() {
    Alert.alert(
      'Cancelar pedido?',
      `Pedido #${order.orderNumber} será cancelado. Esta ação não pode ser desfeita.`,
      [
        { text: 'Voltar', style: 'cancel' },
        {
          text: 'Cancelar pedido',
          style: 'destructive',
          onPress: () => onCancel(order),
        },
      ],
    );
  }

  const typeLabel = ORDER_TYPE_ICON[order.orderType] ?? order.orderType;

  return (
    <View
      style={{
        borderRadius: 12,
        overflow: 'hidden',
        marginHorizontal: 6,
        marginVertical: 5,
        backgroundColor: theme.bg.primary,
        // shadow Android
        elevation: 2,
      }}
      accessibilityRole="none"
      accessibilityLabel={`Pedido ${order.orderNumber}, ${typeLabel}`}
    >
      {/* Barra de aging no topo */}
      <AgingBar order={order} now={now} />

      {/* Corpo do card */}
      <View style={{ padding: 12 }}>
        {/* Cabeçalho: número + timer + botão cancelar */}
        <View style={{ flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 6 }}>
          <View style={{ flex: 1 }}>
            <Text style={{ fontSize: 24, fontWeight: '700', color: theme.text.primary, lineHeight: 28 }}>
              #{order.orderNumber}
            </Text>
            <Text style={{ fontSize: 13, color: theme.text.secondary, marginTop: 2 }}>
              {typeLabel}
              {order.tableNumber ? ` · Mesa ${order.tableNumber}` : ''}
            </Text>
          </View>
          <View style={{ alignItems: 'flex-end', gap: 6 }}>
            <TimerText createdAt={order.createdAt} order={order} />
            {/* Botão cancelar — 48dp mínimo */}
            <Pressable
              onPress={handleCancel}
              android_ripple={{ color: semantic.error.light }}
              accessibilityLabel="Cancelar pedido"
              accessibilityRole="button"
              style={({ pressed }) => ({
                minHeight: 48,
                minWidth: 48,
                justifyContent: 'center',
                alignItems: 'center',
                opacity: pressed ? 0.6 : 1,
              })}
            >
              <Text style={{ fontSize: 22, color: theme.text.muted }}>✕</Text>
            </Pressable>
          </View>
        </View>

        {/* Itens do pedido */}
        <View style={{ gap: 6 }}>
          {order.items.map((item, i) => (
            <View key={`${item.productName}-${i}`}>
              <Text style={{ fontSize: 18, fontWeight: '500', color: theme.text.primary }}>
                {item.quantity}× {item.productName}
              </Text>
              {item.notes ? (
                <Text style={{ fontSize: 13, color: semantic.warning.dark, marginTop: 2 }}>
                  ⚠ {item.notes}
                </Text>
              ) : null}
            </View>
          ))}
        </View>
      </View>

      {/* Botão de avançar — largura total, 52dp, arredondado na base */}
      {nextStatus && advanceLabel ? (
        <Pressable
          onPress={handleAdvance}
          disabled={advancing}
          android_ripple={{ color: 'rgba(255,255,255,0.2)' }}
          accessibilityLabel={`${advanceLabel} — pedido #${order.orderNumber}`}
          accessibilityRole="button"
          style={({ pressed }) => ({
            minHeight: 52,
            backgroundColor: advancing ? '#94a3b8' : advanceColor,
            justifyContent: 'center',
            alignItems: 'center',
            opacity: pressed ? 0.85 : 1,
          })}
        >
          <Text style={{ color: '#ffffff', fontWeight: '600', fontSize: 15 }}>
            {advancing ? 'Aguarde...' : advanceLabel}
          </Text>
        </Pressable>
      ) : null}
    </View>
  );
}
