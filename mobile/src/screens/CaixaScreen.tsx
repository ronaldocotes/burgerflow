// Tela de CAIXA (CashSession) — fase M4. Habilita a venda em dinheiro no app:
// o PdvService exige uma sessao OPEN para pagamento CASH (409 caso contrario).
// Acesso ADMIN/MANAGER/CASHIER (a aba so aparece para esses papeis; o backend
// tambem valida). 4 estados: loading, erro, caixa fechado (abrir) e aberto
// (resumo teorico + Sangria/Reforco/Fechar).
import React, { useCallback, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useFocusEffect } from '@react-navigation/native';
import { Snackbar } from 'react-native-paper';
import {
  ArrowDown,
  ArrowUp,
  Lock,
  LockOpen,
  Money,
  WarningCircle,
} from 'phosphor-react-native';

import { ApiError } from '@/lib/api';
import { getCurrentSession, openSession } from '@/api/caixa';
import { formatBRL, parseBRLToCents } from '@/utils/money';
import { palette, semantic, theme } from '@/theme/colors';
import { EntrySheet, type EntrySheetRef } from '@/components/caixa/EntrySheet';
import {
  CloseCashSheet,
  type CloseCashSheetRef,
} from '@/components/caixa/CloseCashSheet';
import type { CashSession } from '@/types/caixa';

type LoadState = 'loading' | 'error' | 'closed' | 'open';

function formatTime(iso: string): string {
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '--:--';
  return d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
}

export default function CaixaScreen() {
  const [state, setState] = useState<LoadState>('loading');
  const [session, setSession] = useState<CashSession | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [snack, setSnack] = useState<string | null>(null);

  // Abertura (estado da tela de caixa fechado)
  const [openAmount, setOpenAmount] = useState('');
  const [opening, setOpening] = useState(false);
  const [openErr, setOpenErr] = useState<string | null>(null);

  const entryRef = useRef<EntrySheetRef>(null);
  const closeRef = useRef<CloseCashSheetRef>(null);
  const didInitialLoad = useRef(false);

  const load = useCallback(async (silent = false) => {
    if (!silent) setState('loading');
    try {
      const s = await getCurrentSession();
      setSession(s);
      setState(s ? 'open' : 'closed');
    } catch {
      setState('error');
    }
  }, []);

  // Recarrega o estado do caixa sempre que a aba ganha foco, evitando
  // valores velhos (ex.: apos uma venda em dinheiro feita no PDV). O
  // primeiro foco faz a carga completa (spinner); os seguintes recarregam
  // em silencio para nao piscar a tela. `load` e estavel (useCallback).
  useFocusEffect(
    useCallback(() => {
      void load(didInitialLoad.current);
      didInitialLoad.current = true;
    }, [load]),
  );

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await load(true);
    setRefreshing(false);
  }, [load]);

  const doOpen = useCallback(
    async (cents: number, confirmZero: boolean) => {
      if (opening) return;
      setOpening(true);
      setOpenErr(null);
      try {
        const s = await openSession({
          openingAmountCents: cents,
          confirmZeroOpening: confirmZero,
        });
        setSession(s);
        setState('open');
        setOpenAmount('');
        setSnack('Caixa aberto.');
      } catch (e) {
        setOpenErr(
          e instanceof ApiError ? e.message : 'Falha ao abrir o caixa.',
        );
      } finally {
        setOpening(false);
      }
    },
    [opening],
  );

  const handleOpenPress = useCallback(() => {
    const cents = parseBRLToCents(openAmount) ?? 0;
    if (cents === 0) {
      Alert.alert(
        'Abrir caixa com R$ 0,00?',
        'Nenhum fundo de troco inicial será registrado.',
        [
          { text: 'Cancelar', style: 'cancel' },
          { text: 'Abrir assim', onPress: () => void doOpen(0, true) },
        ],
      );
      return;
    }
    void doOpen(cents, false);
  }, [openAmount, doOpen]);

  const handleSheetSuccess = useCallback(
    async (message: string) => {
      setSnack(message);
      await load(true);
    },
    [load],
  );

  let content: React.ReactNode = null;

  if (state === 'loading') {
    content = (
      <View style={styles.center}>
        <ActivityIndicator size="large" color={theme.brand} />
        <Text style={styles.centerText}>Carregando o caixa...</Text>
      </View>
    );
  } else if (state === 'error') {
    content = (
      <View style={styles.center}>
        <WarningCircle color={semantic.error.DEFAULT} size={56} weight="fill" />
        <Text style={styles.centerTitle}>Erro ao carregar</Text>
        <Text style={styles.centerText}>
          Não foi possivel consultar o caixa. Verifique a conexao.
        </Text>
        <Pressable
          style={styles.primaryBtn}
          onPress={() => void load()}
          accessibilityRole="button"
          accessibilityLabel="Tentar novamente"
        >
          <Text style={styles.primaryBtnText}>Tentar novamente</Text>
        </Pressable>
      </View>
    );
  } else if (state === 'closed') {
    content = (
      <ScrollView
        contentContainerStyle={styles.scroll}
        keyboardShouldPersistTaps="handled"
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            tintColor={theme.brand}
          />
        }
      >
        <View style={styles.statusRow}>
          <Lock color={theme.text.muted} size={22} weight="fill" />
          <Text style={styles.statusClosed}>Caixa fechado</Text>
        </View>
        <Text style={styles.helper}>
          Abra o caixa com o fundo de troco inicial para liberar vendas em
          dinheiro no PDV.
        </Text>

        <View style={styles.card}>
          <Text style={styles.label}>Fundo de troco inicial (R$)</Text>
          <TextInput
            style={styles.amountInput}
            value={openAmount}
            onChangeText={setOpenAmount}
            placeholder="0,00"
            placeholderTextColor={theme.text.muted}
            keyboardType="numeric"
            returnKeyType="done"
            accessibilityLabel="Fundo de troco inicial em reais"
          />
          {openErr ? (
            <Text style={styles.errorText} accessibilityRole="alert">
              {openErr}
            </Text>
          ) : null}
          <Pressable
            style={[styles.primaryBtn, opening && styles.btnDisabled]}
            onPress={handleOpenPress}
            disabled={opening}
            accessibilityRole="button"
            accessibilityLabel="Abrir caixa"
          >
            <LockOpen color={theme.text.onBrand} size={20} weight="fill" />
            <Text style={styles.primaryBtnText}>
              {opening ? 'Abrindo...' : 'Abrir caixa'}
            </Text>
          </Pressable>
        </View>
      </ScrollView>
    );
  } else if (session) {
    const s = session;
    content = (
      <ScrollView
        contentContainerStyle={styles.scroll}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={onRefresh}
            tintColor={theme.brand}
          />
        }
      >
        <View style={styles.statusRow}>
          <LockOpen color={theme.brand} size={22} weight="fill" />
          <Text style={styles.statusOpen}>Caixa aberto</Text>
          <Text style={styles.statusTime}>desde {formatTime(s.openedAt)}</Text>
        </View>

        <View style={styles.card}>
          <SummaryRow label="Saldo inicial" cents={s.openingAmountCents} />
          <SummaryRow label="Vendas em dinheiro" cents={s.cashSalesCents} />
          <SummaryRow
            label="Reforcos"
            cents={s.depositsCents}
            sign="+"
            tone="pos"
          />
          <SummaryRow
            label="Sangrias"
            cents={s.withdrawalsCents}
            sign="-"
            tone="neg"
          />
          <View style={styles.divider} />
          <SummaryRow label="Saldo teorico" cents={s.expectedCents} strong />
        </View>

        <View style={styles.actionsRow}>
          <Pressable
            style={styles.actionBtn}
            onPress={() => entryRef.current?.open('WITHDRAWAL', s.id)}
            accessibilityRole="button"
            accessibilityLabel="Registrar sangria"
          >
            <ArrowUp color={semantic.error.DEFAULT} size={22} weight="bold" />
            <Text style={styles.actionBtnText}>Sangria</Text>
          </Pressable>
          <Pressable
            style={styles.actionBtn}
            onPress={() => entryRef.current?.open('DEPOSIT', s.id)}
            accessibilityRole="button"
            accessibilityLabel="Registrar reforco"
          >
            <ArrowDown color={theme.brand} size={22} weight="bold" />
            <Text style={styles.actionBtnText}>Reforco</Text>
          </Pressable>
        </View>

        <Pressable
          style={styles.closeBtn}
          onPress={() => closeRef.current?.open(s)}
          accessibilityRole="button"
          accessibilityLabel="Fechar caixa"
        >
          <Lock color={theme.text.onBrand} size={20} weight="fill" />
          <Text style={styles.closeBtnText}>Fechar caixa</Text>
        </Pressable>
      </ScrollView>
    );
  }

  return (
    <SafeAreaView style={styles.safe} edges={['top']}>
      <View style={styles.header}>
        <Money color={theme.text.primary} size={22} weight="fill" />
        <Text style={styles.headerTitle}>Caixa</Text>
      </View>

      {content}

      <EntrySheet ref={entryRef} onSuccess={handleSheetSuccess} />
      <CloseCashSheet ref={closeRef} onSuccess={handleSheetSuccess} />

      <Snackbar
        visible={!!snack}
        onDismiss={() => setSnack(null)}
        duration={3000}
      >
        {snack ?? ''}
      </Snackbar>
    </SafeAreaView>
  );
}

function SummaryRow({
  label,
  cents,
  sign,
  tone,
  strong,
}: {
  label: string;
  cents: number;
  sign?: '+' | '-';
  tone?: 'pos' | 'neg';
  strong?: boolean;
}) {
  const prefix = sign && cents > 0 ? `${sign} ` : '';
  return (
    <View style={styles.row}>
      <Text style={[styles.rowLabel, strong && styles.rowLabelStrong]}>
        {label}
      </Text>
      <Text
        style={[
          styles.rowValue,
          strong && styles.rowValueStrong,
          tone === 'pos' && cents > 0 && styles.rowValuePos,
          tone === 'neg' && cents > 0 && styles.rowValueNeg,
        ]}
      >
        {prefix}
        {formatBRL(cents)}
      </Text>
    </View>
  );
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: theme.bg.secondary },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    paddingHorizontal: 20,
    paddingTop: 12,
    paddingBottom: 8,
    backgroundColor: theme.bg.primary,
    borderBottomWidth: 1,
    borderBottomColor: theme.border.light,
  },
  headerTitle: {
    fontSize: 20,
    fontWeight: '800',
    color: theme.text.primary,
  },
  center: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 32,
    gap: 12,
  },
  centerTitle: {
    fontSize: 20,
    fontWeight: '800',
    color: theme.text.primary,
    textAlign: 'center',
  },
  centerText: {
    fontSize: 15,
    color: theme.text.secondary,
    textAlign: 'center',
    lineHeight: 21,
  },
  scroll: { padding: 20, gap: 16 },
  statusRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  statusClosed: {
    fontSize: 17,
    fontWeight: '800',
    color: theme.text.secondary,
  },
  statusOpen: { fontSize: 17, fontWeight: '800', color: theme.brand },
  statusTime: { fontSize: 14, color: theme.text.muted, marginLeft: 2 },
  helper: {
    fontSize: 14,
    color: theme.text.secondary,
    lineHeight: 20,
    marginTop: -4,
  },
  card: {
    backgroundColor: theme.bg.primary,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: theme.border.light,
    padding: 20,
    gap: 14,
  },
  label: { fontSize: 14, fontWeight: '600', color: theme.text.secondary },
  amountInput: {
    borderWidth: 1.5,
    borderColor: theme.border.medium,
    borderRadius: 10,
    padding: 14,
    fontSize: 20,
    fontWeight: '700',
    color: theme.text.primary,
    backgroundColor: theme.bg.secondary,
    fontVariant: ['tabular-nums'],
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  rowLabel: { fontSize: 15, color: theme.text.secondary },
  rowLabelStrong: { fontSize: 16, fontWeight: '700', color: theme.text.primary },
  rowValue: {
    fontSize: 15,
    fontWeight: '600',
    color: theme.text.primary,
    fontVariant: ['tabular-nums'],
  },
  rowValueStrong: { fontSize: 20, fontWeight: '800', color: theme.brand },
  rowValuePos: { color: semantic.success.DEFAULT },
  rowValueNeg: { color: semantic.error.DEFAULT },
  divider: {
    height: 1,
    backgroundColor: theme.border.light,
    marginVertical: 2,
  },
  actionsRow: { flexDirection: 'row', gap: 12 },
  actionBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    minHeight: 56,
    borderRadius: 14,
    borderWidth: 1.5,
    borderColor: theme.border.medium,
    backgroundColor: theme.bg.primary,
  },
  actionBtnText: {
    fontSize: 15,
    fontWeight: '700',
    color: theme.text.primary,
  },
  closeBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
    minHeight: 56,
    borderRadius: 14,
    backgroundColor: palette.neutral[700],
  },
  closeBtnText: {
    fontSize: 16,
    fontWeight: '700',
    color: theme.text.onBrand,
  },
  primaryBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
    minHeight: 56,
    borderRadius: 14,
    paddingHorizontal: 24,
    backgroundColor: palette.primary[700],
  },
  primaryBtnText: {
    fontSize: 16,
    fontWeight: '700',
    color: theme.text.onBrand,
  },
  btnDisabled: { opacity: 0.5 },
  errorText: {
    fontSize: 13,
    color: semantic.error.DEFAULT,
    textAlign: 'center',
  },
});
