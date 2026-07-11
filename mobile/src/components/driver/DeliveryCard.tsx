// Card de entrega na lista "Minhas entregas". Alvo de toque inteiro >= 48dp.
import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { CaretRight, MapPin } from 'phosphor-react-native';

import { StatusChip } from './StatusChip';
import { formatBRL } from '@/utils/money';
import { fullAddress } from '@/utils/deliveryStatus';
import { theme } from '@/theme/colors';
import type { DeliveryOrder } from '@/types/delivery';

interface DeliveryCardProps {
  order: DeliveryOrder;
  onPress: () => void;
}

export function DeliveryCard({ order, onPress }: DeliveryCardProps) {
  const address = fullAddress(order);
  return (
    <Pressable
      style={({ pressed }) => [styles.card, pressed && styles.pressed]}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={`Pedido ${order.orderNumber}, abrir detalhes`}
    >
      <View style={styles.body}>
        <View style={styles.topRow}>
          <View style={styles.orderLeft}>
            {order.deliverySequence != null && (
              <View
                style={styles.seqBadge}
                accessibilityLabel={`Parada ${order.deliverySequence} da rota`}
              >
                <Text style={styles.seqBadgeText}>{order.deliverySequence}</Text>
              </View>
            )}
            <Text style={styles.orderNumber}>#{order.orderNumber}</Text>
          </View>
          <Text style={styles.total}>{formatBRL(order.totalCents)}</Text>
        </View>
        <View style={styles.addressRow}>
          <MapPin color={theme.text.secondary} size={18} weight="fill" />
          <Text style={styles.address} numberOfLines={2}>
            {address || 'Endereco nao informado'}
          </Text>
        </View>
        <StatusChip status={order.deliveryStatus} />
      </View>
      <CaretRight color={theme.text.muted} size={24} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  card: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: theme.bg.primary,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: theme.border.light,
    padding: 16,
    marginBottom: 12,
    minHeight: 96,
  },
  pressed: { opacity: 0.85 },
  body: { flex: 1, gap: 8 },
  topRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  orderLeft: { flexDirection: 'row', alignItems: 'center', gap: 8, flexShrink: 1 },
  seqBadge: {
    minWidth: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: theme.brand,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 6,
  },
  seqBadgeText: {
    color: theme.text.onBrand,
    fontSize: 15,
    fontWeight: '800',
    fontVariant: ['tabular-nums'],
  },
  orderNumber: { fontSize: 18, fontWeight: '800', color: theme.text.primary },
  total: {
    fontSize: 18,
    fontWeight: '700',
    color: theme.text.primary,
    fontVariant: ['tabular-nums'],
  },
  addressRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 6, paddingRight: 8 },
  address: { flex: 1, fontSize: 15, color: theme.text.secondary, lineHeight: 20 },
});
