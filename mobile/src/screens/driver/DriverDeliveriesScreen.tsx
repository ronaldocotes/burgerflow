// Minhas entregas (Fase 6.2): lista das entregas ativas do motoboy.
// 4 estados obrigatorios: loading / vazio / erro / sucesso.
import React, { useCallback, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Moped, WarningCircle } from 'phosphor-react-native';

import { StatusBanner } from '@/components/ui/StatusBanner';
import { DeliveryCard } from '@/components/driver/DeliveryCard';
import { useDriver } from '@/context/DriverContext';
import { semantic, theme } from '@/theme/colors';
import type { DriverDeliveriesStackParamList } from '@/navigation/DriverTabs';
import type { DeliveryOrder } from '@/types/delivery';

type ListNav = NativeStackNavigationProp<DriverDeliveriesStackParamList, 'DeliveriesList'>;

export default function DriverDeliveriesScreen() {
  const navigation = useNavigation<ListNav>();
  const { deliveries, deliveriesState, refreshDeliveries, feedStatus } = useDriver();
  const [refreshing, setRefreshing] = useState(false);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await refreshDeliveries();
    setRefreshing(false);
  }, [refreshDeliveries]);

  const renderItem = useCallback(
    ({ item }: { item: DeliveryOrder }) => (
      <DeliveryCard
        order={item}
        onPress={() => navigation.navigate('DeliveryDetail', { orderId: item.orderId })}
      />
    ),
    [navigation],
  );

  let content: React.ReactNode;
  if (deliveriesState === 'loading') {
    content = (
      <View style={styles.center}>
        <ActivityIndicator size="large" color={theme.brand} />
        <Text style={styles.centerText}>Carregando entregas...</Text>
      </View>
    );
  } else if (deliveriesState === 'error') {
    content = (
      <View style={styles.center}>
        <WarningCircle color={semantic.error.DEFAULT} size={56} weight="fill" />
        <Text style={styles.centerTitle}>Erro ao carregar</Text>
        <Text style={styles.centerText}>
          Nao foi possivel buscar suas entregas. Verifique a conexao.
        </Text>
        <Pressable
          style={({ pressed }) => [styles.retryBtn, pressed && styles.pressed]}
          onPress={refreshDeliveries}
          accessibilityRole="button"
          accessibilityLabel="Tentar novamente"
        >
          <Text style={styles.retryLabel}>TENTAR NOVAMENTE</Text>
        </Pressable>
      </View>
    );
  } else if (deliveries.length === 0) {
    content = (
      <View style={styles.center}>
        <Moped color={theme.text.muted} size={64} />
        <Text style={styles.centerTitle}>Nenhuma entrega ativa</Text>
        <Text style={styles.centerText}>
          Fique ONLINE na tela Inicio para receber ofertas de entrega.
        </Text>
      </View>
    );
  } else {
    content = (
      <FlatList
        data={deliveries}
        keyExtractor={(o) => o.orderId}
        renderItem={renderItem}
        contentContainerStyle={styles.list}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} tintColor={theme.brand} />
        }
      />
    );
  }

  return (
    <SafeAreaView style={styles.safe}>
      <StatusBanner feedStatus={feedStatus} pollingLabel="Atualizando a cada 15s" />
      <Text style={styles.title}>Minhas entregas</Text>
      {content}
    </SafeAreaView>
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
  list: { padding: 20, paddingTop: 8 },
  center: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 32, gap: 12 },
  centerTitle: {
    fontSize: 20,
    fontWeight: '800',
    color: theme.text.primary,
    textAlign: 'center',
  },
  centerText: { fontSize: 16, color: theme.text.secondary, textAlign: 'center', lineHeight: 22 },
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
