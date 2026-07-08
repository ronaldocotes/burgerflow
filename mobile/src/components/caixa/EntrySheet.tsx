// Bottom sheet de Sangria (WITHDRAWAL) / Reforco (DEPOSIT) do Caixa.
// Um unico componente atende as duas operacoes (mesmo endpoint /entries; o
// backend distingue pelo campo type). Campos: valor (R$) e motivo (obrigatorio).
// Trava de duplo clique via submitting/disabled (o endpoint NAO aceita
// Idempotency-Key — contrato confirmado no CashSessionController).
import React, {
  forwardRef,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { BottomSheetModal, BottomSheetView } from '@gorhom/bottom-sheet';
import { semantic, theme } from '@/theme/colors';
import { formatBRL, parseBRLToCents } from '@/utils/money';
import { addEntry } from '@/api/caixa';
import { ApiError } from '@/lib/api';
import { renderBackdrop } from '@/components/pdv/sheetUtils';
import type { CashEntryType } from '@/types/caixa';

export interface EntrySheetRef {
  open: (type: CashEntryType, sessionId: string) => void;
}

interface EntrySheetProps {
  onSuccess: (message: string) => void;
}

const META: Record<
  CashEntryType,
  { title: string; verb: string; hint: string; placeholder: string }
> = {
  WITHDRAWAL: {
    title: 'Sangria',
    verb: 'Registrar sangria',
    hint: 'Retirada de dinheiro da gaveta (reduz o saldo teorico).',
    placeholder: 'Ex.: troco, pagamento de fornecedor',
  },
  DEPOSIT: {
    title: 'Reforco',
    verb: 'Registrar reforco',
    hint: 'Entrada de dinheiro na gaveta (aumenta o saldo teorico).',
    placeholder: 'Ex.: aporte de troco',
  },
};

export const EntrySheet = forwardRef<EntrySheetRef, EntrySheetProps>(
  ({ onSuccess }, ref) => {
    const sheetRef = useRef<BottomSheetModal>(null);
    const [type, setType] = useState<CashEntryType>('WITHDRAWAL');
    const [sessionId, setSessionId] = useState('');
    const [amount, setAmount] = useState('');
    const [reason, setReason] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [err, setErr] = useState<string | null>(null);

    useImperativeHandle(ref, () => ({
      open(t, id) {
        setType(t);
        setSessionId(id);
        setAmount('');
        setReason('');
        setErr(null);
        setSubmitting(false);
        sheetRef.current?.present();
      },
    }));

    const amountCents = parseBRLToCents(amount);
    const reasonTrimmed = reason.trim();
    const canSubmit =
      !submitting &&
      amountCents != null &&
      amountCents > 0 &&
      reasonTrimmed.length > 0;

    const meta = META[type];

    async function confirm() {
      if (!canSubmit || amountCents == null) return;
      setErr(null);
      setSubmitting(true);
      try {
        await addEntry(sessionId, {
          type,
          amountCents,
          reason: reasonTrimmed,
        });
        sheetRef.current?.dismiss();
        onSuccess(`${meta.title} de ${formatBRL(amountCents)} registrada.`);
      } catch (e) {
        setErr(
          e instanceof ApiError
            ? e.message
            : 'Falha ao registrar. Tente novamente.',
        );
      } finally {
        setSubmitting(false);
      }
    }

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
          <Text style={styles.title}>{meta.title}</Text>
          <Text style={styles.hint}>{meta.hint}</Text>

          <Text style={styles.label}>Valor (R$)</Text>
          <TextInput
            style={styles.input}
            value={amount}
            onChangeText={setAmount}
            placeholder="0,00"
            placeholderTextColor={theme.text.muted}
            keyboardType="numeric"
            returnKeyType="next"
            accessibilityLabel={`Valor da ${meta.title.toLowerCase()} em reais`}
          />

          <Text style={styles.label}>Motivo</Text>
          <TextInput
            style={[styles.input, styles.reasonInput]}
            value={reason}
            onChangeText={setReason}
            placeholder={meta.placeholder}
            placeholderTextColor={theme.text.muted}
            maxLength={255}
            multiline
            accessibilityLabel="Motivo"
          />

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
              onPress={() => void confirm()}
              disabled={!canSubmit}
              accessibilityRole="button"
              accessibilityLabel={meta.verb}
            >
              <Text style={styles.confirmBtnText}>
                {submitting ? 'Registrando...' : meta.verb}
              </Text>
            </Pressable>
          </View>
        </BottomSheetView>
      </BottomSheetModal>
    );
  },
);

EntrySheet.displayName = 'EntrySheet';

const styles = StyleSheet.create({
  bg: {
    backgroundColor: theme.bg.primary,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
  },
  handle: { backgroundColor: theme.border.medium, width: 40 },
  container: { flex: 1, padding: 20, gap: 12 },
  title: { fontSize: 20, fontWeight: '800', color: theme.text.primary },
  hint: { fontSize: 13, color: theme.text.secondary, marginBottom: 4 },
  label: {
    fontSize: 14,
    fontWeight: '600',
    color: theme.text.secondary,
  },
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
  reasonInput: {
    fontSize: 15,
    fontWeight: '400',
    minHeight: 56,
    textAlignVertical: 'top',
    fontVariant: [],
  },
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
    backgroundColor: theme.brand,
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
