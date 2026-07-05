// Detalhe da entrega (Fase 6.2): endereco completo, contato do cliente,
// deep-links Maps/Waze e avanco de status com trava de duplo clique.
import React, { useRef, useState } from 'react';
import {
  ActivityIndicator,
  Linking,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Snackbar } from 'react-native-paper';
import { useNavigation, useRoute } from '@react-navigation/native';
import type { RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import {
  CaretLeft,
  CheckCircle,
  MapPin,
  NavigationArrow,
  Phone,
  WarningCircle,
} from 'phosphor-react-native';

import { updateOrderStatus } from '@/api/delivery';
import { StatusChip } from '@/components/driver/StatusChip';
import { useDriver } from '@/context/DriverContext';
import { formatBRL } from '@/utils/money';
import { fullAddress, nextStep } from '@/utils/deliveryStatus';
import { semantic, theme } from '@/theme/colors';
import type { DriverDeliveriesStackParamList } from '@/navigation/DriverTabs';
import type { DeliveryOrder } from '@/types/delivery';

type DetailRoute = RouteProp<DriverDeliveriesStackParamList, 'DeliveryDetail'>;
type DetailNav = NativeStackNavigationProp<DriverDeliveriesStackParamList, 'DeliveryDetail'>;

// --- Deep-links de navegacao (fallback web quando o app nao esta instalado) ---
function openMaps(order: DeliveryOrder) {
  const q = encodeURIComponent(fullAddress(order));
  const hasCoords = order.deliveryLat != null && order.deliveryLng != null;
  const geo = hasCoords
    ? `geo:${order.deliveryLat},${order.deliveryLng}?q=${order.deliveryLat},${order.deliveryLng}(${q})`
    : `geo:0,0?q=${q}`;
  Linking.openURL(geo).catch(() => {
    Linking.openURL(`https://www.google.com/maps/search/?api=1&query=${q}`).catch(() => {});
  });
}

function openWaze(order: DeliveryOrder) {
  const q = encodeURIComponent(fullAddress(order));
  const hasCoords = order.deliveryLat != null && order.deliveryLng != null;
  const url = hasCoords
    ? `waze://?ll=${order.deliveryLat},${order.deliveryLng}&navigate=yes`
    : `waze://?q=${q}&navigate=yes`;
  Linking.openURL(url).catch(() => {
    Linking.openURL(`https://waze.com/ul?q=${q}&navigate=yes`).catch(() => {});
  });
}

function callCustomer(phone: string) {
  Linking.openURL(`tel:${phone}`).catch(() => {});
}

// Pagamento: informacao critica para o motoboy (cobrar ou nao na porta).
function paymentInfo(status: string): { label: string; tone: 'ok' | 'charge' | 'neutral' } {
  switch (status) {
    case 'PAID':
      return { label: 'PAGO — nao cobrar', tone: 'ok' };
    case 'PENDING':
    case 'FAILED':
      return { label: 'RECEBER NA ENTREGA', tone: 'charge' };
    case 'REFUNDED':
      return { label: 'Reembolsado — confirme com a loja', tone: 'neutral' };
    default:
      return { label: status, tone: 'neutral' };
  }
}

export default function DriverDeliveryDetailScreen() {
  const route = useRoute<DetailRoute>();
  const navigation = useNavigation<DetailNav>();
  const { deliveries, refreshDeliveries } = useDriver();

  const order = deliveries.find((o) => o.orderId === route.params.orderId);

  const [advancing, setAdvancing] = useState(false);
  const advanceLock = useRef(false);
  const [snack, setSnack] = useState<string | null>(null);

  async function handleAdvance(current: DeliveryOrder) {
    const step = nextStep(current.deliveryStatus);
    // Trava de duplo clique: ref sincrona + estado visual.
    if (!step || advanceLock.current) return;
    advanceLock.current = true;
    setAdvancing(true);
    try {
      await updateOrderStatus(current.orderId, step.next);
      await refreshDeliveries();
      if (step.next === 'DELIVERED') {
        setSnack('Entrega concluida. Bom trabalho!');
      } else {
        setSnack('Status atualizado.');
      }
    } catch {
      setSnack('Nao foi possivel atualizar o status. Tente de novo.');
    } finally {
      advanceLock.current = false;
      setAdvancing(false);
    }
  }

  if (!order) {
    return (
      <SafeAreaView style={styles.safe}>
        <Header onBack={() => navigation.goBack()} title="Entrega" />
        <View style={styles.center}>
          <CheckCircle color={theme.brand} size={56} weight="fill" />
          <Text style={styles.centerTitle}>Entrega nao encontrada</Text>
          <Text style={styles.centerText}>
            Ela pode ja ter sido concluida ou repassada. Volte para a lista.
          </Text>
        </View>
      </SafeAreaView>
    );
  }

  const step = nextStep(order.deliveryStatus);
  const payment = paymentInfo(order.paymentStatus);
  const addressLine1 = order.deliveryStreet
    ? `${order.deliveryStreet}${order.deliveryNumber ? `, ${order.deliveryNumber}` : ''}`
    : 'Endereco nao informado';

  return (
    <SafeAreaView style={styles.safe}>
      <Header onBack={() => navigation.goBack()} title={`Pedido #${order.orderNumber}`} />
      <ScrollView contentContainerStyle={styles.scroll}>
        <StatusChip status={order.deliveryStatus} size="lg" />

        {/* Endereco */}
        <View style={styles.card}>
          <View style={styles.cardHeaderRow}>
            <MapPin color={theme.brand} size={24} weight="fill" />
            <Text style={styles.cardTitle}>Entregar em</Text>
          </View>
          {!!order.deliveryRecipientName && (
            <Text style={styles.recipient}>{order.deliveryRecipientName}</Text>
          )}
          <Text style={styles.addressMain}>{addressLine1}</Text>
          {!!order.deliveryComplement && (
            <Text style={styles.addressLine}>{order.deliveryComplement}</Text>
          )}
          <Text style={styles.addressLine}>
            {[order.deliveryNeighborhood, order.deliveryCity].filter(Boolean).join(' - ')}
          </Text>
          {!!order.deliveryReference && (
            <Text style={styles.reference}>Referencia: {order.deliveryReference}</Text>
          )}

          {/* Acoes: ligar / navegar */}
          <View style={styles.actionsRow}>
            {!!order.deliveryPhone && (
              <ActionButton
                label="LIGAR"
                icon={<Phone color={theme.brand} size={24} weight="fill" />}
                onPress={() => callCustomer(order.deliveryPhone as string)}
                accessibilityLabel="Ligar para o cliente"
              />
            )}
            <ActionButton
              label="MAPS"
              icon={<MapPin color={theme.brand} size={24} weight="fill" />}
              onPress={() => openMaps(order)}
              accessibilityLabel="Abrir endereco no Maps"
            />
            <ActionButton
              label="WAZE"
              icon={<NavigationArrow color={theme.brand} size={24} weight="fill" />}
              onPress={() => openWaze(order)}
              accessibilityLabel="Abrir endereco no Waze"
            />
          </View>
        </View>

        {/* Valores */}
        <View style={styles.card}>
          <View style={styles.valueRow}>
            <Text style={styles.valueLabel}>Total do pedido</Text>
            <Text style={styles.valueAmount}>{formatBRL(order.totalCents)}</Text>
          </View>
          <View style={styles.valueRow}>
            <Text style={styles.valueLabel}>Taxa de entrega</Text>
            <Text style={styles.valueAmount}>{formatBRL(order.deliveryFeeCents)}</Text>
          </View>
          <View
            style={[
              styles.paymentBox,
              payment.tone === 'ok' && styles.paymentOk,
              payment.tone === 'charge' && styles.paymentCharge,
            ]}
            accessibilityRole="alert"
          >
            {payment.tone === 'charge' ? (
              <WarningCircle color={semantic.warning.dark} size={24} weight="fill" />
            ) : (
              <CheckCircle
                color={payment.tone === 'ok' ? semantic.success.dark : theme.text.secondary}
                size={24}
                weight="fill"
              />
            )}
            <Text
              style={[
                styles.paymentText,
                payment.tone === 'ok' && styles.paymentTextOk,
                payment.tone === 'charge' && styles.paymentTextCharge,
              ]}
            >
              {payment.label}
            </Text>
          </View>
        </View>

        {/* Avanco de status */}
        {step && (
          <Pressable
            style={({ pressed }) => [styles.advanceBtn, (pressed || advancing) && styles.pressed]}
            onPress={() => handleAdvance(order)}
            disabled={advancing}
            accessibilityRole="button"
            accessibilityLabel={step.label}
            accessibilityState={{ disabled: advancing, busy: advancing }}
          >
            {advancing ? (
              <ActivityIndicator color={theme.text.onBrand} />
            ) : (
              <Text style={styles.advanceLabel}>{step.label}</Text>
            )}
          </Pressable>
        )}
      </ScrollView>

      <Snackbar visible={!!snack} onDismiss={() => setSnack(null)} duration={3000}>
        {snack}
      </Snackbar>
    </SafeAreaView>
  );
}

// --- Subcomponentes ---
function Header({ onBack, title }: { onBack: () => void; title: string }) {
  return (
    <View style={styles.header}>
      <Pressable
        style={({ pressed }) => [styles.backBtn, pressed && styles.pressed]}
        onPress={onBack}
        accessibilityRole="button"
        accessibilityLabel="Voltar"
        hitSlop={8}
      >
        <CaretLeft color={theme.text.primary} size={28} weight="bold" />
      </Pressable>
      <Text style={styles.headerTitle} numberOfLines={1}>
        {title}
      </Text>
    </View>
  );
}

function ActionButton({
  label,
  icon,
  onPress,
  accessibilityLabel,
}: {
  label: string;
  icon: React.ReactNode;
  onPress: () => void;
  accessibilityLabel: string;
}) {
  return (
    <Pressable
      style={({ pressed }) => [styles.actionBtn, pressed && styles.pressed]}
      onPress={onPress}
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel}
    >
      {icon}
      <Text style={styles.actionLabel}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: theme.bg.secondary },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  backBtn: {
    width: 48,
    height: 48,
    borderRadius: 24,
    justifyContent: 'center',
    alignItems: 'center',
  },
  headerTitle: { flex: 1, fontSize: 22, fontWeight: '800', color: theme.text.primary },
  scroll: { padding: 20, paddingTop: 8, paddingBottom: 40, gap: 16 },
  center: { flex: 1, justifyContent: 'center', alignItems: 'center', padding: 32, gap: 12 },
  centerTitle: { fontSize: 20, fontWeight: '800', color: theme.text.primary, textAlign: 'center' },
  centerText: { fontSize: 16, color: theme.text.secondary, textAlign: 'center', lineHeight: 22 },

  card: {
    backgroundColor: theme.bg.primary,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: theme.border.light,
    padding: 20,
    gap: 6,
  },
  cardHeaderRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 4 },
  cardTitle: { fontSize: 16, fontWeight: '700', color: theme.text.secondary },
  recipient: { fontSize: 18, fontWeight: '800', color: theme.text.primary },
  addressMain: { fontSize: 20, fontWeight: '700', color: theme.text.primary, lineHeight: 26 },
  addressLine: { fontSize: 16, color: theme.text.secondary, lineHeight: 22 },
  reference: {
    fontSize: 15,
    color: theme.text.secondary,
    fontStyle: 'italic',
    marginTop: 4,
    lineHeight: 20,
  },
  actionsRow: { flexDirection: 'row', gap: 10, marginTop: 14 },
  actionBtn: {
    flex: 1,
    height: 64,
    borderRadius: 14,
    borderWidth: 2,
    borderColor: theme.brand,
    justifyContent: 'center',
    alignItems: 'center',
    gap: 2,
    backgroundColor: theme.bg.primary,
  },
  actionLabel: { fontSize: 13, fontWeight: '800', color: theme.brand, letterSpacing: 0.5 },

  valueRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 4,
  },
  valueLabel: { fontSize: 16, color: theme.text.secondary, fontWeight: '600' },
  valueAmount: {
    fontSize: 18,
    fontWeight: '800',
    color: theme.text.primary,
    fontVariant: ['tabular-nums'],
  },
  paymentBox: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    borderRadius: 12,
    padding: 14,
    marginTop: 8,
    backgroundColor: theme.bg.tertiary,
  },
  paymentOk: { backgroundColor: semantic.success.light },
  paymentCharge: { backgroundColor: semantic.warning.light },
  paymentText: { flex: 1, fontSize: 16, fontWeight: '800', color: theme.text.secondary },
  paymentTextOk: { color: semantic.success.dark },
  paymentTextCharge: { color: semantic.warning.dark },

  advanceBtn: {
    height: 72,
    borderRadius: 16,
    backgroundColor: theme.brand,
    justifyContent: 'center',
    alignItems: 'center',
  },
  advanceLabel: {
    color: theme.text.onBrand,
    fontSize: 22,
    fontWeight: '800',
    letterSpacing: 1,
  },
  pressed: { opacity: 0.8 },
});
