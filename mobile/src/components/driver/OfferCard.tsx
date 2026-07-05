// Card de OFERTA de entrega: contador regressivo + ACEITAR/RECUSAR gigantes.
// Persona: motoboy no sol, uma mao, pressa — texto grande, alvos >= 56dp.
import React from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { MapTrifold, Moped, Timer } from 'phosphor-react-native';

import { formatBRL } from '@/utils/money';
import { delivery as deliveryColors, semantic, theme } from '@/theme/colors';
import type { DeliveryOffer } from '@/types/delivery';

interface OfferCardProps {
  offer: DeliveryOffer;
  secondsLeft: number;
  acting: 'accept' | 'reject' | null;
  onAccept: () => void;
  onReject: () => void;
}

function formatCountdown(totalSeconds: number): string {
  const m = Math.floor(totalSeconds / 60);
  const s = totalSeconds % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

export function OfferCard({ offer, secondsLeft, acting, onAccept, onReject }: OfferCardProps) {
  const urgent = secondsLeft <= 10;
  const countdownColor = urgent ? semantic.error.DEFAULT : deliveryColors.offered;

  return (
    <View style={styles.card} accessibilityLabel="Nova oferta de entrega">
      <View style={styles.headerRow}>
        <Moped color={deliveryColors.offered} size={28} weight="fill" />
        <Text style={styles.headerText}>NOVA ENTREGA</Text>
        <View style={styles.countdownBox}>
          <Timer color={countdownColor} size={22} weight="fill" />
          <Text
            style={[styles.countdown, { color: countdownColor }]}
            accessibilityLabel={`Expira em ${secondsLeft} segundos`}
          >
            {formatCountdown(secondsLeft)}
          </Text>
        </View>
      </View>

      <Text style={styles.fee}>{formatBRL(offer.feeCents)}</Text>
      <View style={styles.metaRow}>
        <MapTrifold color={theme.text.secondary} size={18} />
        <Text style={styles.metaText}>
          {offer.distanceKm != null
            ? `${offer.distanceKm.toFixed(1).replace('.', ',')} km ate o cliente`
            : 'Distancia nao informada'}
        </Text>
      </View>

      <Pressable
        style={({ pressed }) => [styles.acceptBtn, (pressed || acting != null) && styles.pressed]}
        onPress={onAccept}
        disabled={acting != null}
        accessibilityRole="button"
        accessibilityLabel="Aceitar entrega"
        accessibilityState={{ disabled: acting != null, busy: acting === 'accept' }}
      >
        {acting === 'accept' ? (
          <ActivityIndicator color={theme.text.onBrand} />
        ) : (
          <Text style={styles.acceptLabel}>ACEITAR</Text>
        )}
      </Pressable>

      <Pressable
        style={({ pressed }) => [styles.rejectBtn, (pressed || acting != null) && styles.pressed]}
        onPress={onReject}
        disabled={acting != null}
        accessibilityRole="button"
        accessibilityLabel="Recusar entrega"
        accessibilityState={{ disabled: acting != null, busy: acting === 'reject' }}
      >
        {acting === 'reject' ? (
          <ActivityIndicator color={semantic.error.DEFAULT} />
        ) : (
          <Text style={styles.rejectLabel}>RECUSAR</Text>
        )}
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: theme.bg.primary,
    borderRadius: 20,
    borderWidth: 2,
    borderColor: deliveryColors.offered,
    padding: 20,
    marginBottom: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.12,
    shadowRadius: 16,
    elevation: 6,
  },
  headerRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  headerText: {
    flex: 1,
    fontSize: 18,
    fontWeight: '800',
    color: theme.text.primary,
    letterSpacing: 0.5,
  },
  countdownBox: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  countdown: {
    fontSize: 26,
    fontWeight: '800',
    fontVariant: ['tabular-nums'],
  },
  fee: {
    fontSize: 44,
    fontWeight: '800',
    color: theme.text.primary,
    marginTop: 12,
    fontVariant: ['tabular-nums'],
  },
  metaRow: { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 4, marginBottom: 20 },
  metaText: { fontSize: 16, color: theme.text.secondary, fontWeight: '600' },
  acceptBtn: {
    height: 72,
    borderRadius: 16,
    backgroundColor: theme.brand,
    justifyContent: 'center',
    alignItems: 'center',
  },
  acceptLabel: {
    color: theme.text.onBrand,
    fontSize: 24,
    fontWeight: '800',
    letterSpacing: 1,
  },
  rejectBtn: {
    height: 56,
    borderRadius: 16,
    borderWidth: 2,
    borderColor: semantic.error.DEFAULT,
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: 12,
    backgroundColor: theme.bg.primary,
  },
  rejectLabel: {
    color: semantic.error.DEFAULT,
    fontSize: 18,
    fontWeight: '800',
    letterSpacing: 1,
  },
  pressed: { opacity: 0.8 },
});
