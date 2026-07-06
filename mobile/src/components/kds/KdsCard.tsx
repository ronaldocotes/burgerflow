// Card individual de pedido no board do KDS.
// Todos os botões >= 48dp. Avançar: barra inteira no fundo do card, com trava
// de duplo toque. Cancelar: abre o CancelModal (motivo obrigatório) na tela.
// Número principal: ID do canal externo quando houver (iFood etc.); o número
// interno vira referência secundária — mesma regra do KDS web.

import React, { useEffect, useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { AgingBar } from './AgingBar';
import { OriginBadge } from './OriginBadge';
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

  const typeLabel = ORDER_TYPE_ICON[order.orderType] ?? order.orderType;

  // Quando existe ID do canal externo, ele vira o número principal do card.
  const displayNumber = order.externalDisplayId ?? `#${order.orderNumber}`;
  const showInternalRef =
    !!order.externalDisplayId &&
    order.externalDisplayId !== `#${order.orderNumber}`;

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
        {/* Cabeçalho: número + badge de origem + timer + botão cancelar */}
        <View style={{ flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 6 }}>
          <View style={{ flex: 1 }}>
            <View style={{ flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 6 }}>
              <Text style={{ fontSize: 24, fontWeight: '700', color: theme.text.primary, lineHeight: 28 }}>
                {displayNumber}
              </Text>
              <OriginBadge origin={order.externalOrigin} />
            </View>
            {showInternalRef ? (
              <Text style={{ fontSize: 12, color: theme.text.muted, marginTop: 1 }}>
                int #{order.orderNumber}
              </Text>
            ) : null}
            <Text style={{ fontSize: 13, color: theme.text.secondary, marginTop: 2 }}>
              {typeLabel}
              {order.tableNumber ? ` · Mesa ${order.tableNumber}` : ''}
            </Text>
          </View>
          <View style={{ alignItems: 'flex-end', gap: 6 }}>
            <TimerText createdAt={order.createdAt} order={order} />
            {/* Botão cancelar — 48dp mínimo; confirmação + motivo no CancelModal */}
            <Pressable
              onPress={() => onCancel(order)}
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
            backgroundColor: advancing ? theme.text.muted : advanceColor,
            justifyContent: 'center',
            alignItems: 'center',
            opacity: pressed ? 0.85 : 1,
          })}
        >
          <Text style={{ color: theme.text.onBrand, fontWeight: '600', fontSize: 15 }}>
            {advancing ? 'Aguarde...' : advanceLabel}
          </Text>
        </Pressable>
      ) : null}
    </View>
  );
}
