// Bottom sheet de Fechamento do Caixa. Mostra o saldo teorico, recebe o valor
// real contado na gaveta e calcula a diferenca (sobra/falta) ANTES de confirmar.
// Fechamento e irreversivel -> confirmacao via Alert. Trava de duplo clique via
// submitting/disabled (endpoint /close NAO aceita Idempotency-Key).
import React, {
  forwardRef,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import {
  Alert,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { BottomSheetModal, BottomSheetView } from '@gorhom/bottom-sheet';
import { semantic, theme } from '@/theme/colors';
import { formatBRL, parseBRLToCents } from '@/utils/money';
import { closeSession } from '@/api/caixa';
import { ApiError } from '@/lib/api';
import { renderBackdrop } from '@/components/pdv/sheetUtils';
import type { CashSession } from '@/types/caixa';

export interface CloseCashSheetRef {
  open: (session: CashSession) => void;
}

interface CloseCashSheetProps {
  onSuccess: (message: string) => void;
}

export const CloseCashSheet = forwardRef<CloseCashSheetRef, CloseCashSheetProps>(
  ({ onSuccess }, ref) => {
    const sheetRef = useRef<BottomSheetModal>(null);
    const [session, setSession] = useState<CashSession | null>(null);
    const [counted, setCounted] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [err, setErr] = useState<string | null>(null);

    useImperativeHandle(ref, () => ({
      open(s) {
        setSession(s);
        setCounted('');
        setErr(null);
        setSubmitting(false);
        sheetRef.current?.present();
      },
    }));

    const countedCents = parseBRLToCents(counted);
    const expectedCents = session?.expectedCents ?? 0;
    const diffCents = countedCents != null ? countedCents - expectedCents : null;
    const canSubmit = !submitting && countedCents != null && countedCents >= 0;

    async function doClose() {
      if (!session || countedCents == null) return;
      setErr(null);
      setSubmitting(true);
      try {
        await closeSession(session.id, { countedAmountCents: countedCents });
        sheetRef.current?.dismiss();
        onSuccess('Caixa fechado.');
      } catch (e) {
        setErr(
          e instanceof ApiError ? e.message : 'Falha ao fechar o caixa.',
        );
      } finally {
        setSubmitting(false);
      }
    }

    function confirm() {
      if (!canSubmit || diffCents == null) return;
      const diffLabel =
        diffCents === 0
          ? 'O valor confere com o saldo teorico.'
          : diffCents > 0
            ? `Sobra de ${formatBRL(diffCents)} sobre o teorico.`
            : `Falta de ${formatBRL(Math.abs(diffCents))} sobre o teorico.`;
      Alert.alert(
        'Fechar o caixa?',
        `${diffLabel}\n\nEssa ação encerra o turno e não pode ser desfeita.`,
        [
          { text: 'Cancelar', style: 'cancel' },
          {
            text: 'Fechar caixa',
            style: 'destructive',
            onPress: () => void doClose(),
          },
        ],
      );
    }

    const diffText =
      diffCents == null
        ? '--'
        : diffCents === 0
          ? `${formatBRL(0)} (confere)`
          : diffCents > 0
            ? `+ ${formatBRL(diffCents)} (sobra)`
            : `- ${formatBRL(Math.abs(diffCents))} (falta)`;

    return (
      <BottomSheetModal
        ref={sheetRef}
        snapPoints={['55%', '85%']}
        enableDynamicSizing={false}
        backdropComponent={renderBackdrop}
        backgroundStyle={styles.bg}
        handleIndicatorStyle={styles.handle}
        onDismiss={() => setErr(null)}
      >
        <BottomSheetView style={styles.container}>
          <Text style={styles.title}>Fechar caixa</Text>

          <View style={styles.expectedRow}>
            <Text style={styles.expectedLabel}>Saldo teorico</Text>
            <Text style={styles.expectedValue}>{formatBRL(expectedCents)}</Text>
          </View>

          <Text style={styles.label}>Valor contado na gaveta (R$)</Text>
          <TextInput
            style={styles.input}
            value={counted}
            onChangeText={setCounted}
            placeholder="0,00"
            placeholderTextColor={theme.text.muted}
            keyboardType="numeric"
            returnKeyType="done"
            accessibilityLabel="Valor real contado em reais"
          />

          <View style={styles.diffRow}>
            <Text style={styles.diffLabel}>Diferenca</Text>
            <Text
              style={[
                styles.diffValue,
                diffCents != null && diffCents > 0 && styles.diffPos,
                diffCents != null && diffCents < 0 && styles.diffNeg,
                diffCents === 0 && styles.diffOk,
              ]}
            >
              {diffText}
            </Text>
          </View>

          {err ? (
            <Text style={styles.errorText} accessibilityRole="alert">
              {err}
            </Text>
          ) : null}

          <View style={styles.buttonRow}>
            <Pressable
              style={styles.cancelBtn}
              onPress={() => sheetRef.current?.dismiss()}
              disabled={submitting}
              accessibilityRole="button"
              accessibilityLabel="Cancelar"
            >
              <Text style={styles.cancelBtnText}>Cancelar</Text>
            </Pressable>
            <Pressable
              style={[styles.confirmBtn, !canSubmit && styles.confirmBtnDisabled]}
              onPress={confirm}
              disabled={!canSubmit}
              accessibilityRole="button"
              accessibilityLabel="Revisar e fechar caixa"
            >
              <Text style={styles.confirmBtnText}>
                {submitting ? 'Fechando...' : 'Fechar caixa'}
              </Text>
            </Pressable>
          </View>
        </BottomSheetView>
      </BottomSheetModal>
    );
  },
);

CloseCashSheet.displayName = 'CloseCashSheet';

const styles = StyleSheet.create({
  bg: {
    backgroundColor: theme.bg.primary,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
  },
  handle: { backgroundColor: theme.border.medium, width: 40 },
  container: { flex: 1, padding: 20, gap: 12 },
  title: { fontSize: 20, fontWeight: '800', color: theme.text.primary },
  expectedRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    backgroundColor: theme.bg.secondary,
    borderRadius: 12,
    paddingHorizontal: 16,
    paddingVertical: 14,
  },
  expectedLabel: { fontSize: 15, color: theme.text.secondary, fontWeight: '600' },
  expectedValue: {
    fontSize: 17,
    fontWeight: '800',
    color: theme.text.primary,
    fontVariant: ['tabular-nums'],
  },
  label: { fontSize: 14, fontWeight: '600', color: theme.text.secondary },
  input: {
    borderWidth: 1.5,
    borderColor: theme.border.medium,
    borderRadius: 10,
    padding: 14,
    fontSize: 18,
    fontWeight: '600',
    color: theme.text.primary,
    backgroundColor: theme.bg.secondary,
    fontVariant: ['tabular-nums'],
  },
  diffRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 4,
  },
  diffLabel: { fontSize: 15, color: theme.text.secondary, fontWeight: '600' },
  diffValue: {
    fontSize: 16,
    fontWeight: '800',
    color: theme.text.primary,
    fontVariant: ['tabular-nums'],
  },
  diffPos: { color: semantic.success.DEFAULT },
  diffNeg: { color: semantic.error.DEFAULT },
  diffOk: { color: theme.text.secondary },
  errorText: {
    fontSize: 13,
    color: semantic.error.DEFAULT,
    textAlign: 'center',
  },
  buttonRow: { flexDirection: 'row', gap: 12, marginTop: 8 },
  cancelBtn: {
    flex: 1,
    minHeight: 52,
    borderRadius: 12,
    borderWidth: 1.5,
    borderColor: theme.border.medium,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cancelBtnText: {
    fontSize: 15,
    fontWeight: '600',
    color: theme.text.secondary,
  },
  confirmBtn: {
    flex: 2,
    minHeight: 52,
    backgroundColor: semantic.error.DEFAULT,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  confirmBtnDisabled: { opacity: 0.4 },
  confirmBtnText: {
    fontSize: 15,
    fontWeight: '700',
    color: theme.text.onBrand,
  },
});
