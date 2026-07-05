// Ganhos do motoboy (Fase 6.2): total do dia e da semana em R$.
// Consome GET /delivery/earnings/my (contrato PREVISTO — backend em paralelo);
// enquanto o endpoint nao existe, mostra estado de erro especifico.
import React, { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { CalendarBlank, Sun, Wallet, WarningCircle } from 'phosphor-react-native';

import { ApiError } from '@/lib/api';
import { getMyEarnings } from '@/api/delivery';
import { formatBRL } from '@/utils/money';
import { semantic, theme } from '@/theme/colors';
import type { EarningsSummary } from '@/types/delivery';

type LoadState = 'loading' | 'error' | 'unavailable' | 'ready';

export default function DriverEarningsScreen() {
  const [state, setState] = useState<LoadState>('loading');
  const [data, setData] = useState<EarningsSummary | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    try {
      const summary = await getMyEarnings();
      setData(summary);
      setState('ready');
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) setState('unavailable');
      else setState('error');
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await load();
    setRefreshing(false);
  }, [load]);

  let content: React.ReactNode;
  if (state === 'loading') {
    content = (
      <View style={styles.center}>
        <ActivityIndicator size="large" color={theme.brand} />
        <Text style={styles.centerText}>Calculando seus ganhos...</Text>
      </View>
    );
  } else if (state === 'unavailable') {
    content = (
      <View style={styles.center}>
        <Wallet color={theme.text.muted} size={64} />
        <Text style={styles.centerTitle}>Ganhos ainda nao disponiveis</Text>
        <Text style={styles.centerText}>
          O resumo de ganhos esta sendo preparado no servidor. Em breve voce vera
          aqui quanto fez no dia e na semana.
        </Text>
        <RetryButton onPress={load} />
      </View>
    );
  } else if (state === 'error' || !data) {
    content = (
      <View style={styles.center}>
        <WarningCircle color={semantic.error.DEFAULT} size={56} weight="fill" />
        <Text style={styles.centerTitle}>Erro ao carregar</Text>
        <Text style={styles.centerText}>
          Nao foi possivel buscar seus ganhos. Verifique a conexao.
        </Text>
        <RetryButton onPress={load} />
      </View>
    );
  } else {
    const todayCents = data.todayCents ?? 0;
    const weekCents = data.weekCents ?? 0;
    const todayDeliveries = data.todayDeliveries ?? 0;
    const weekDeliveries = data.weekDeliveries ?? 0;
    content = (
      <ScrollView
        contentContainerStyle={styles.scroll}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} tintColor={theme.brand} />
        }
      >
        <EarningsCard
          icon={<Sun color={theme.brand} size={28} weight="fill" />}
          title="HOJE"
          cents={todayCents}
          deliveries={todayDeliveries}
          highlight
        />
        <EarningsCard
          icon={<CalendarBlank color={theme.text.secondary} size={28} weight="fill" />}
          title="ESTA SEMANA"
          cents={weekCents}
          deliveries={weekDeliveries}
        />
        <Text style={styles.hint}>
          Valores somam as taxas das entregas concluidas. Puxe para baixo para atualizar.
        </Text>
      </ScrollView>
    );
  }

  return (
    <SafeAreaView style={styles.safe}>
      <Text style={styles.title}>Meus ganhos</Text>
      {content}
    </SafeAreaView>
  );
}

function EarningsCard({
  icon,
  title,
  cents,
  deliveries,
  highlight = false,
}: {
  icon: React.ReactNode;
  title: string;
  cents: number;
  deliveries: number;
  highlight?: boolean;
}) {
  return (
    <View style={[styles.card, highlight && styles.cardHighlight]}>
      <View style={styles.cardHeaderRow}>
        {icon}
        <Text style={styles.cardTitle}>{title}</Text>
      </View>
      <Text
        style={styles.amount}
        accessibilityLabel={`${title}: ${formatBRL(cents)}, ${deliveries} entregas`}
      >
        {formatBRL(cents)}
      </Text>
      <Text style={styles.deliveriesCount}>
        {deliveries === 1 ? '1 entrega concluida' : `${deliveries} entregas concluidas`}
      </Text>
    </View>
  );
}

function RetryButton({ onPress }: { onPress: () => void }) {
  return (
    <Pressable
      style={({ pressed }) => [styles.retryBtn, pressed && styles.pressed]}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel="Tentar novamente"
    >
      <Text style={styles.retryLabel}>TENTAR NOVAMENTE</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: theme.bg.secondary },
  title: {
    fontSize: 24,
    fontWeight: '800',
    color: theme.text.primary,
    paddingHorizontal: 20,
    paddingTop: 16,
    paddingBottom: 8,
  },
  scroll: { padding: 20, paddingTop: 8, gap: 16 },
  center: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 32, gap: 12 },
  centerTitle: { fontSize: 20, fontWeight: '800', color: theme.text.primary, textAlign: 'center' },
  centerText: { fontSize: 16, color: theme.text.secondary, textAlign: 'center', lineHeight: 22 },

  card: {
    backgroundColor: theme.bg.primary,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: theme.border.light,
    padding: 24,
    gap: 8,
  },
  cardHighlight: { borderColor: theme.brand, borderWidth: 2 },
  cardHeaderRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  cardTitle: {
    fontSize: 15,
    fontWeight: '800',
    color: theme.text.secondary,
    letterSpacing: 1,
  },
  amount: {
    fontSize: 44,
    fontWeight: '800',
    color: theme.text.primary,
    fontVariant: ['tabular-nums'],
  },
  deliveriesCount: { fontSize: 16, color: theme.text.secondary, fontWeight: '600' },
  hint: { fontSize: 14, color: theme.text.muted, textAlign: 'center', lineHeight: 20 },

  retryBtn: {
    height: 56,
    minWidth: 220,
    borderRadius: 14,
    backgroundColor: theme.brand,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 24,
    marginTop: 8,
  },
  retryLabel: { color: theme.text.onBrand, fontSize: 16, fontWeight: '800', letterSpacing: 0.5 },
  pressed: { opacity: 0.8 },
});
