// Home do motoboy (Fase 6.2): toggle grande ONLINE/OFFLINE + card de oferta
// com contador regressivo. Persona: uma mao, sol, pressa — tudo grande.
import React, { useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { Snackbar } from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { Moped, Power, WarningCircle } from 'phosphor-react-native';

import { StatusBanner } from '@/components/ui/StatusBanner';
import { OfferCard } from '@/components/driver/OfferCard';
import { useDriver } from '@/context/DriverContext';
import { useOffersFeed } from '@/hooks/useOffersFeed';
import { isActiveDelivery } from '@/utils/deliveryStatus';
import { delivery as deliveryColors, semantic, theme } from '@/theme/colors';
import type { DriverTabParamList } from '@/navigation/DriverTabs';

type HomeNav = BottomTabNavigationProp<DriverTabParamList, 'Inicio'>;

export default function DriverHomeScreen() {
  const navigation = useNavigation<HomeNav>();
  const {
    profile,
    profileState,
    reloadProfile,
    shiftBusy,
    toggleShift,
    deliveries,
    refreshDeliveries,
    gpsPermission,
  } = useDriver();

  const online = !!profile?.activeShift;
  const offers = useOffersFeed({
    driverId: profile?.id ?? null,
    enabled: online,
    onAccepted: refreshDeliveries,
  });

  const [snack, setSnack] = useState<string | null>(null);
  const activeCount = deliveries.filter(isActiveDelivery).length;

  async function handleToggle() {
    const wasOnline = online;
    const err = await toggleShift();
    setSnack(
      err ??
        (wasOnline
          ? 'Voce esta OFFLINE. Ate a proxima!'
          : 'Voce esta ONLINE. Aguardando ofertas...'),
    );
  }

  async function handleAccept() {
    setSnack(await offers.accept());
  }

  async function handleReject() {
    setSnack(await offers.reject());
  }

  // --- Estados do perfil (loading / sem vinculo / erro) ---
  if (profileState === 'loading') {
    return (
      <SafeAreaView style={styles.safe}>
        <View style={styles.center}>
          <ActivityIndicator size="large" color={theme.brand} />
          <Text style={styles.centerText}>Carregando seu perfil...</Text>
        </View>
      </SafeAreaView>
    );
  }

  if (profileState === 'unlinked' || profileState === 'error') {
    const unlinked = profileState === 'unlinked';
    return (
      <SafeAreaView style={styles.safe}>
        <View style={styles.center}>
          <WarningCircle color={semantic.warning.DEFAULT} size={56} weight="fill" />
          <Text style={styles.centerTitle}>
            {unlinked ? 'Cadastro de entregador nao encontrado' : 'Erro ao carregar'}
          </Text>
          <Text style={styles.centerText}>
            {unlinked
              ? 'Seu usuario ainda nao esta vinculado a um entregador deste restaurante. Fale com o gerente.'
              : 'Nao foi possivel carregar seu perfil. Verifique a conexao.'}
          </Text>
          <Pressable
            style={({ pressed }) => [styles.retryBtn, pressed && styles.pressed]}
            onPress={reloadProfile}
            accessibilityRole="button"
            accessibilityLabel="Tentar novamente"
          >
            <Text style={styles.retryLabel}>TENTAR NOVAMENTE</Text>
          </Pressable>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.safe}>
      {online && (
        <StatusBanner
          feedStatus={offers.feedStatus}
          pollingLabel="Buscando ofertas a cada 15s"
        />
      )}
      <ScrollView contentContainerStyle={styles.scroll}>
        <Text style={styles.greeting}>Ola, {profile?.name ?? 'entregador'}</Text>

        {/* Toggle de turno — alvo gigante (uma mao, no sol) */}
        <Pressable
          style={({ pressed }) => [
            styles.shiftBtn,
            online ? styles.shiftOn : styles.shiftOff,
            (pressed || shiftBusy) && styles.pressed,
          ]}
          onPress={handleToggle}
          disabled={shiftBusy}
          accessibilityRole="switch"
          accessibilityLabel={online ? 'Ficar offline' : 'Ficar online'}
          accessibilityState={{ checked: online, disabled: shiftBusy, busy: shiftBusy }}
        >
          {shiftBusy ? (
            <ActivityIndicator
              size="large"
              color={online ? theme.text.onBrand : deliveryColors.offline}
            />
          ) : (
            <>
              <Power
                color={online ? theme.text.onBrand : deliveryColors.offline}
                size={44}
                weight="bold"
              />
              <Text style={[styles.shiftTitle, online && styles.shiftTitleOn]}>
                {online ? 'VOCE ESTA ONLINE' : 'VOCE ESTA OFFLINE'}
              </Text>
              <Text style={[styles.shiftSub, online && styles.shiftSubOn]}>
                {online
                  ? 'Recebendo ofertas. Toque para pausar.'
                  : 'Toque para comecar a receber entregas.'}
              </Text>
            </>
          )}
        </Pressable>

        {/* Aviso de permissao de GPS negada */}
        {online && gpsPermission === 'denied' && (
          <View style={styles.gpsWarning} accessibilityRole="alert">
            <WarningCircle color={semantic.warning.dark} size={24} weight="fill" />
            <Text style={styles.gpsWarningText}>
              Sem permissao de localizacao. Ative em Configuracoes do Android para
              o restaurante acompanhar suas entregas.
            </Text>
          </View>
        )}

        {/* Oferta ativa */}
        {online && offers.offer && (
          <OfferCard
            offer={offers.offer}
            secondsLeft={offers.secondsLeft}
            acting={offers.acting}
            onAccept={handleAccept}
            onReject={handleReject}
          />
        )}

        {/* Resumo de entregas ativas */}
        <View style={styles.deliveriesCard}>
          <View style={styles.deliveriesRow}>
            <Moped color={theme.brand} size={32} weight="fill" />
            <View style={styles.deliveriesTextBox}>
              <Text style={styles.deliveriesCount}>
                {activeCount === 0
                  ? 'Nenhuma entrega em andamento'
                  : activeCount === 1
                    ? '1 entrega em andamento'
                    : `${activeCount} entregas em andamento`}
              </Text>
              {online && activeCount === 0 && (
                <Text style={styles.deliveriesHint}>
                  Fique por aqui: a proxima oferta aparece nesta tela.
                </Text>
              )}
            </View>
          </View>
          <Pressable
            style={({ pressed }) => [styles.deliveriesBtn, pressed && styles.pressed]}
            onPress={() => navigation.navigate('Entregas')}
            accessibilityRole="button"
            accessibilityLabel="Ver minhas entregas"
          >
            <Text style={styles.deliveriesBtnLabel}>VER MINHAS ENTREGAS</Text>
          </Pressable>
        </View>
      </ScrollView>

      <Snackbar visible={!!snack} onDismiss={() => setSnack(null)} duration={3000}>
        {snack}
      </Snackbar>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: theme.bg.secondary },
  scroll: { padding: 20, paddingBottom: 40 },
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

  greeting: { fontSize: 24, fontWeight: '800', color: theme.text.primary, marginBottom: 16 },

  shiftBtn: {
    minHeight: 150,
    borderRadius: 24,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
    gap: 6,
    marginBottom: 16,
  },
  shiftOn: { backgroundColor: deliveryColors.online },
  shiftOff: {
    backgroundColor: theme.bg.primary,
    borderWidth: 2,
    borderColor: theme.border.medium,
  },
  shiftTitle: {
    fontSize: 24,
    fontWeight: '800',
    color: deliveryColors.offline,
    letterSpacing: 0.5,
  },
  shiftTitleOn: { color: theme.text.onBrand },
  shiftSub: { fontSize: 15, fontWeight: '600', color: theme.text.secondary, textAlign: 'center' },
  shiftSubOn: { color: theme.text.onBrand },

  gpsWarning: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    backgroundColor: semantic.warning.light,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: semantic.warning.DEFAULT,
    padding: 14,
    marginBottom: 16,
  },
  gpsWarningText: {
    flex: 1,
    fontSize: 14,
    fontWeight: '600',
    color: semantic.warning.dark,
    lineHeight: 19,
  },

  deliveriesCard: {
    backgroundColor: theme.bg.primary,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: theme.border.light,
    padding: 20,
    gap: 16,
  },
  deliveriesRow: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  deliveriesTextBox: { flex: 1, gap: 2 },
  deliveriesCount: { fontSize: 17, fontWeight: '700', color: theme.text.primary },
  deliveriesHint: { fontSize: 14, color: theme.text.secondary, lineHeight: 19 },
  deliveriesBtn: {
    height: 56,
    borderRadius: 14,
    borderWidth: 2,
    borderColor: theme.brand,
    justifyContent: 'center',
    alignItems: 'center',
  },
  deliveriesBtnLabel: { color: theme.brand, fontSize: 16, fontWeight: '800', letterSpacing: 0.5 },
  pressed: { opacity: 0.8 },
});
