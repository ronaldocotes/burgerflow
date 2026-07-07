import React, {
  forwardRef,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { BottomSheetModal, BottomSheetView } from '@gorhom/bottom-sheet';
import { semantic, theme } from '@/theme/colors';
import { formatBRL, uuid } from '@/utils/money';
import { api, ApiError } from '@/lib/api';
import type { OrderCreateInput, OrderItemInput, OrderType, PaymentMethod, QuoteResponse } from '@/types/cart';
import { renderBackdrop } from './sheetUtils';

const PAYMENT_LABELS: Record<PaymentMethod, string> = {
  CASH: 'Dinheiro',
  CREDIT_CARD: 'Credito',
  DEBIT_CARD: 'Debito',
  PIX: 'Pix',
  OTHER: 'Outro',
};

export interface PaymentSheetRef {
  open: (quote: QuoteResponse, orderType: OrderType, items: OrderItemInput[]) => void;
}

interface PaymentSheetProps {
  onConfirmed: (orderNumber: string) => void;
}

// Bottom sheet modal de pagamento. Forma de pagamento em chips; campo Dinheiro
// com teclado numerico e calculo de troco local; anti-duplo-pedido via
// Idempotency-Key + disabled durante request.
export const PaymentSheet = forwardRef<PaymentSheetRef, PaymentSheetProps>(
  ({ onConfirmed }, ref) => {
    const sheetRef = useRef<BottomSheetModal>(null);
    const [quote, setQuote] = useState<QuoteResponse | null>(null);
    const [orderType, setOrderType] = useState<OrderType>('DINE_IN');
    const [items, setItems] = useState<OrderItemInput[]>([]);

    const [method, setMethod] = useState<PaymentMethod>('CASH');
    const [received, setReceived] = useState('');
    const [submitting, setSubmitting] = useState(false);
    const [err, setErr] = useState<string | null>(null);
    // Idempotency-Key por TENTATIVA de pedido (gerada no open, reutilizada em
    // retries): se o POST criou o pedido mas a resposta caiu, o retry com a
    // mesma chave nao duplica. O web gera no clique; aqui e mais defensivo.
    const idempotencyKeyRef = useRef<string>(uuid());

    useImperativeHandle(ref, () => ({
      open(q, ot, its) {
        setQuote(q);
        setOrderType(ot);
        setItems(its);
        setMethod('CASH');
        setReceived('');
        setErr(null);
        setSubmitting(false);
        idempotencyKeyRef.current = uuid();
        sheetRef.current?.present();
      },
    }));

    // Troco calculado localmente (so troco, total vem do servidor).
    const receivedCents = useMemo(() => {
      const normalized = received.replace(/\./g, '').replace(',', '.');
      const value = parseFloat(normalized);
      return isFinite(value) ? Math.round(value * 100) : null;
    }, [received]);

    const changeCents =
      method === 'CASH' && receivedCents != null && quote != null
        ? receivedCents - quote.totalCents
        : null;

    const insufficientCash =
      method === 'CASH' &&
      receivedCents != null &&
      quote != null &&
      receivedCents < quote.totalCents;

    async function confirm() {
      if (submitting || !quote) return;
      setErr(null);
      setSubmitting(true);
      try {
        const body: OrderCreateInput = {
          orderType,
          items,
          paymentMethod: method,
        };
        const res = await api.post<{ orderNumber: string }>('/orders', body, {
          'Idempotency-Key': idempotencyKeyRef.current,
        });
        sheetRef.current?.dismiss();
        onConfirmed(res?.orderNumber ?? '');
      } catch (e) {
        setErr(
          e instanceof ApiError ? e.message : 'Falha ao registrar o pedido.',
        );
      } finally {
        setSubmitting(false);
      }
    }

    return (
      <BottomSheetModal
        ref={sheetRef}
        snapPoints={['60%', '80%']}
        backdropComponent={renderBackdrop}
        enableDynamicSizing={false}
        onDismiss={() => setErr(null)}
      >
        <BottomSheetView style={styles.container}>
          {/* Cabecalho */}
          <View style={styles.header}>
            <Text style={styles.headerTitle}>Pagamento</Text>
            {quote && (
              <Text style={styles.headerTotal}>
                {formatBRL(quote.totalCents)}
              </Text>
            )}
          </View>

          {/* Formas de pagamento */}
          <Text style={styles.label}>Forma de pagamento</Text>
          <View style={styles.methodGrid}>
            {(Object.keys(PAYMENT_LABELS) as PaymentMethod[]).map((m) => (
              <Pressable
                key={m}
                style={[
                  styles.methodBtn,
                  method === m && styles.methodBtnActive,
                ]}
                onPress={() => setMethod(m)}
                accessibilityRole="radio"
                accessibilityState={{ selected: method === m }}
              >
                <Text
                  style={[
                    styles.methodBtnText,
                    method === m && styles.methodBtnTextActive,
                  ]}
                >
                  {PAYMENT_LABELS[m]}
                </Text>
              </Pressable>
            ))}
          </View>

          {/* Campo de troco (apenas Dinheiro) */}
          {method === 'CASH' && (
            <View style={styles.cashSection}>
              <Text style={styles.label}>Valor recebido (R$)</Text>
              <TextInput
                style={styles.receivedInput}
                value={received}
                onChangeText={setReceived}
                placeholder="0,00"
                placeholderTextColor={theme.text.muted}
                keyboardType="numeric"
                returnKeyType="done"
              />
              <View style={styles.changeRow}>
                <Text style={styles.changeLabel}>Troco</Text>
                <Text
                  style={[
                    styles.changeValue,
                    insufficientCash && styles.changeValueInsufficient,
                  ]}
                >
                  {changeCents == null
                    ? '--'
                    : insufficientCash
                      ? 'Valor insuficiente'
                      : formatBRL(changeCents)}
                </Text>
              </View>
            </View>
          )}

          {/* Erro */}
          {err ? (
            <Text style={styles.errorText} accessibilityRole="alert">
              {err}
            </Text>
          ) : null}

          {/* Botoes */}
          <View style={styles.buttonRow}>
            <Pressable
              style={styles.cancelBtn}
              onPress={() => sheetRef.current?.dismiss()}
              disabled={submitting}
            >
              <Text style={styles.cancelBtnText}>Cancelar</Text>
            </Pressable>
            <Pressable
              style={[
                styles.confirmBtn,
                (submitting || insufficientCash) && styles.confirmBtnDisabled,
              ]}
              onPress={() => void confirm()}
              disabled={submitting || !!insufficientCash}
              accessibilityRole="button"
              accessibilityLabel={
                quote
                  ? `Confirmar pedido ${formatBRL(quote.totalCents)}`
                  : 'Confirmar pedido'
              }
            >
              <Text style={styles.confirmBtnText}>
                {submitting ? 'Registrando...' : 'Confirmar pedido'}
              </Text>
            </Pressable>
          </View>
        </BottomSheetView>
      </BottomSheetModal>
    );
  },
);

PaymentSheet.displayName = 'PaymentSheet';

const styles = StyleSheet.create({
  container: {
    flex: 1,
    padding: 20,
    gap: 16,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  headerTitle: {
    fontSize: 20,
    fontWeight: '700',
    color: theme.text.primary,
  },
  headerTotal: {
    fontSize: 20,
    fontWeight: '800',
    color: theme.brand,
    fontVariant: ['tabular-nums'],
  },
  label: {
    fontSize: 14,
    fontWeight: '600',
    color: theme.text.secondary,
    marginBottom: -4,
  },
  methodGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  methodBtn: {
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 10,
    borderWidth: 1.5,
    borderColor: theme.border.medium,
    backgroundColor: theme.bg.secondary,
  },
  methodBtnActive: {
    backgroundColor: theme.brand,
    borderColor: theme.brand,
  },
  methodBtnText: {
    fontSize: 14,
    fontWeight: '600',
    color: theme.text.secondary,
  },
  methodBtnTextActive: {
    color: theme.text.onBrand,
  },
  cashSection: {
    gap: 8,
  },
  receivedInput: {
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
  changeRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  changeLabel: {
    fontSize: 15,
    color: theme.text.secondary,
    fontWeight: '500',
  },
  changeValue: {
    fontSize: 15,
    fontWeight: '700',
    color: theme.text.primary,
    fontVariant: ['tabular-nums'],
  },
  changeValueInsufficient: {
    color: semantic.error.DEFAULT,
  },
  errorText: {
    fontSize: 13,
    color: semantic.error.DEFAULT,
    textAlign: 'center',
  },
  buttonRow: {
    flexDirection: 'row',
    gap: 12,
    marginTop: 4,
  },
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
  confirmBtnDisabled: {
    opacity: 0.4,
  },
  confirmBtnText: {
    fontSize: 15,
    fontWeight: '700',
    color: theme.text.onBrand,
  },
});
