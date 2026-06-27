import React, { forwardRef, useCallback, useMemo } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import BottomSheet, {
  BottomSheetScrollView,
  BottomSheetView,
} from '@gorhom/bottom-sheet';
import { palette, semantic, theme } from '@/theme/colors';
import { formatBRL } from '@/utils/money';
import type { CartLine, OrderType, QuoteResponse } from '@/types/cart';

const ORDER_TYPE_LABELS: Record<OrderType, string> = {
  DINE_IN: 'Balcao',
  TAKEAWAY: 'Retirada',
  DELIVERY: 'Entrega',
};

interface CartBottomSheetProps {
  lines: CartLine[];
  quote: QuoteResponse | null;
  quoting: boolean;
  quoteError: string | null;
  orderType: OrderType;
  onOrderTypeChange: (t: OrderType) => void;
  onUpdateQty: (lineId: string, qty: number) => void;
  onClear: () => void;
  onFinalize: () => void;
}

// Bottom sheet persistente (sempre na tela) com 3 pontos de encaixe:
// 12% = handle resumo | 50% = lista | 90% = lista + tipo + finalizar
// Persona: Ana/Atendente - velocidade; total sempre visivel no handle.
export const CartBottomSheet = forwardRef<BottomSheet, CartBottomSheetProps>(
  (
    {
      lines,
      quote,
      quoting,
      quoteError,
      orderType,
      onOrderTypeChange,
      onUpdateQty,
      onClear,
      onFinalize,
    },
    ref,
  ) => {
    const snapPoints = useMemo(() => ['12%', '50%', '90%'], []);

    const totalLabel = quoting
      ? '...'
      : quote
        ? formatBRL(quote.totalCents)
        : lines.length > 0
          ? '...'
          : formatBRL(0);

    const renderHandle = useCallback(
      () => (
        <View style={styles.handle}>
          <View style={styles.handleBar} />
          <View style={styles.handleContent}>
            <View style={styles.handleLeft}>
              <View style={styles.badge}>
                <Text style={styles.badgeText}>{lines.length}</Text>
              </View>
              <Text style={styles.handleLabel}>
                {lines.length === 0 ? 'Carrinho vazio' : 'Carrinho'}
              </Text>
            </View>
            <Text style={styles.handleTotal}>{totalLabel}</Text>
          </View>
        </View>
      ),
      [lines.length, totalLabel],
    );

    const canFinalize =
      lines.length > 0 && !quoting && quote != null && !quoteError;

    return (
      <BottomSheet
        ref={ref}
        snapPoints={snapPoints}
        index={0}
        enableDynamicSizing={false}
        handleComponent={renderHandle}
        backgroundStyle={styles.sheetBackground}
      >
        <BottomSheetScrollView
          contentContainerStyle={styles.scrollContent}
          keyboardShouldPersistTaps="handled"
        >
          {lines.length === 0 ? (
            <BottomSheetView style={styles.emptyState}>
              <Text style={styles.emptyText}>
                Toque num produto para adicionar.
              </Text>
            </BottomSheetView>
          ) : (
            <>
              <View style={styles.listHeader}>
                <Text style={styles.listHeaderTitle}>Itens</Text>
                <Pressable onPress={onClear} hitSlop={8}>
                  <Text style={styles.clearBtn}>Limpar</Text>
                </Pressable>
              </View>

              {lines.map((line, idx) => {
                const q = quote?.items[idx];
                return (
                  <View key={line.lineId} style={styles.lineRow}>
                    <View style={styles.lineInfo}>
                      <Text style={styles.lineName} numberOfLines={1}>
                        {line.productName}
                      </Text>
                      {line.label ? (
                        <Text style={styles.lineLabel} numberOfLines={2}>
                          {line.label}
                        </Text>
                      ) : null}
                      <Text style={styles.lineUnit}>
                        {q
                          ? `${formatBRL(q.unitPriceCents)} un.`
                          : quoting
                            ? '...'
                            : '--'}
                      </Text>
                    </View>
                    <View style={styles.qtyControl}>
                      <Pressable
                        style={styles.qtyBtn}
                        onPress={() => onUpdateQty(line.lineId, line.quantity - 1)}
                        accessibilityLabel={`Diminuir ${line.productName}`}
                        hitSlop={4}
                      >
                        <Text style={styles.qtyBtnText}>-</Text>
                      </Pressable>
                      <Text style={styles.qtyValue}>{line.quantity}</Text>
                      <Pressable
                        style={styles.qtyBtn}
                        onPress={() => onUpdateQty(line.lineId, line.quantity + 1)}
                        accessibilityLabel={`Aumentar ${line.productName}`}
                        hitSlop={4}
                      >
                        <Text style={styles.qtyBtnText}>+</Text>
                      </Pressable>
                    </View>
                  </View>
                );
              })}
            </>
          )}

          <View style={styles.section}>
            <Text style={styles.sectionTitle}>Tipo de pedido</Text>
            <View style={styles.orderTypeRow}>
              {(Object.keys(ORDER_TYPE_LABELS) as OrderType[]).map((t) => (
                <Pressable
                  key={t}
                  style={[
                    styles.orderTypeBtn,
                    orderType === t && styles.orderTypeBtnActive,
                  ]}
                  onPress={() => onOrderTypeChange(t)}
                  accessibilityRole="radio"
                  accessibilityState={{ selected: orderType === t }}
                >
                  <Text
                    style={[
                      styles.orderTypeBtnText,
                      orderType === t && styles.orderTypeBtnTextActive,
                    ]}
                  >
                    {ORDER_TYPE_LABELS[t]}
                  </Text>
                </Pressable>
              ))}
            </View>
          </View>

          {quoteError ? (
            <Text style={styles.errorText}>{quoteError}</Text>
          ) : null}

          {quote != null && (
            <View style={styles.totalsSection}>
              <View style={styles.totalRow}>
                <Text style={styles.totalLabel}>Subtotal</Text>
                <Text style={styles.totalValue}>
                  {formatBRL(quote.subtotalCents)}
                </Text>
              </View>
              {quote.discountCents > 0 && (
                <View style={styles.totalRow}>
                  <Text style={styles.totalLabel}>Desconto</Text>
                  <Text style={[styles.totalValue, { color: semantic.success.DEFAULT }]}>
                    -{formatBRL(quote.discountCents)}
                  </Text>
                </View>
              )}
              {quote.deliveryFeeCents > 0 && (
                <View style={styles.totalRow}>
                  <Text style={styles.totalLabel}>Entrega</Text>
                  <Text style={styles.totalValue}>
                    {formatBRL(quote.deliveryFeeCents)}
                  </Text>
                </View>
              )}
              <View style={[styles.totalRow, styles.totalRowFinal]}>
                <Text style={styles.totalFinalLabel}>Total</Text>
                <Text style={styles.totalFinalValue}>
                  {formatBRL(quote.totalCents)}
                </Text>
              </View>
            </View>
          )}

          <Pressable
            style={({ pressed }) => [
              styles.finalizeBtn,
              !canFinalize && styles.finalizeBtnDisabled,
              pressed && canFinalize && styles.finalizeBtnPressed,
            ]}
            onPress={canFinalize ? onFinalize : undefined}
            disabled={!canFinalize}
            accessibilityRole="button"
            accessibilityLabel={
              quote ? `Finalizar ${formatBRL(quote.totalCents)}` : 'Finalizar'
            }
          >
            <Text style={styles.finalizeBtnText}>
              {quoting
                ? 'Calculando...'
                : quote
                  ? `Finalizar ${formatBRL(quote.totalCents)}`
                  : 'Finalizar'}
            </Text>
          </Pressable>
        </BottomSheetScrollView>
      </BottomSheet>
    );
  },
);

CartBottomSheet.displayName = 'CartBottomSheet';

const styles = StyleSheet.create({
  sheetBackground: {
    backgroundColor: theme.bg.primary,
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: -2 },
    shadowOpacity: 0.08,
    shadowRadius: 8,
    elevation: 8,
  },
  handle: {
    paddingHorizontal: 16,
    paddingTop: 10,
    paddingBottom: 8,
    borderBottomWidth: 1,
    borderBottomColor: theme.border.light,
  },
  handleBar: {
    width: 40,
    height: 4,
    backgroundColor: palette.neutral[300],
    borderRadius: 2,
    alignSelf: 'center',
    marginBottom: 10,
  },
  handleContent: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  handleLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  badge: {
    minWidth: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: theme.brand,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 6,
  },
  badgeText: {
    fontSize: 12,
    fontWeight: '700',
    color: theme.text.onBrand,
    fontVariant: ['tabular-nums'],
  },
  handleLabel: {
    fontSize: 14,
    fontWeight: '600',
    color: theme.text.primary,
  },
  handleTotal: {
    fontSize: 16,
    fontWeight: '700',
    color: theme.brand,
    fontVariant: ['tabular-nums'],
  },
  scrollContent: {
    padding: 16,
    paddingBottom: 32,
  },
  emptyState: {
    alignItems: 'center',
    paddingVertical: 24,
  },
  emptyText: {
    fontSize: 14,
    color: theme.text.muted,
    textAlign: 'center',
  },
  listHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 8,
  },
  listHeaderTitle: {
    fontSize: 14,
    fontWeight: '600',
    color: theme.text.secondary,
  },
  clearBtn: {
    fontSize: 13,
    color: semantic.error.DEFAULT,
    fontWeight: '500',
  },
  lineRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: theme.bg.secondary,
    borderRadius: 10,
    padding: 10,
    marginBottom: 8,
    gap: 8,
  },
  lineInfo: {
    flex: 1,
    minWidth: 0,
  },
  lineName: {
    fontSize: 14,
    fontWeight: '600',
    color: theme.text.primary,
  },
  lineLabel: {
    fontSize: 12,
    color: theme.text.muted,
    marginTop: 2,
  },
  lineUnit: {
    fontSize: 12,
    color: theme.text.secondary,
    marginTop: 2,
  },
  qtyControl: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  qtyBtn: {
    width: 40,
    height: 40,
    borderRadius: 8,
    backgroundColor: theme.bg.tertiary,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: theme.border.light,
  },
  qtyBtnText: {
    fontSize: 18,
    fontWeight: '600',
    color: theme.text.primary,
    lineHeight: 20,
  },
  qtyValue: {
    width: 28,
    textAlign: 'center',
    fontSize: 15,
    fontWeight: '700',
    color: theme.text.primary,
    fontVariant: ['tabular-nums'],
  },
  section: {
    marginTop: 16,
    marginBottom: 8,
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: '600',
    color: theme.text.secondary,
    marginBottom: 8,
  },
  orderTypeRow: {
    flexDirection: 'row',
    gap: 8,
  },
  orderTypeBtn: {
    flex: 1,
    paddingVertical: 10,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: theme.border.light,
    alignItems: 'center',
    backgroundColor: theme.bg.secondary,
  },
  orderTypeBtnActive: {
    backgroundColor: theme.brand,
    borderColor: theme.brand,
  },
  orderTypeBtnText: {
    fontSize: 13,
    fontWeight: '600',
    color: theme.text.secondary,
  },
  orderTypeBtnTextActive: {
    color: theme.text.onBrand,
  },
  errorText: {
    fontSize: 13,
    color: semantic.error.DEFAULT,
    marginTop: 8,
    textAlign: 'center',
  },
  totalsSection: {
    marginTop: 16,
    borderTopWidth: 1,
    borderTopColor: theme.border.light,
    paddingTop: 12,
    gap: 6,
  },
  totalRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  totalLabel: {
    fontSize: 14,
    color: theme.text.secondary,
  },
  totalValue: {
    fontSize: 14,
    color: theme.text.primary,
    fontWeight: '500',
    fontVariant: ['tabular-nums'],
  },
  totalRowFinal: {
    marginTop: 6,
    paddingTop: 8,
    borderTopWidth: 1,
    borderTopColor: theme.border.light,
  },
  totalFinalLabel: {
    fontSize: 16,
    fontWeight: '700',
    color: theme.text.primary,
  },
  totalFinalValue: {
    fontSize: 18,
    fontWeight: '800',
    color: theme.brand,
    fontVariant: ['tabular-nums'],
  },
  finalizeBtn: {
    marginTop: 16,
    minHeight: 56,
    backgroundColor: theme.brand,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 16,
  },
  finalizeBtnDisabled: {
    opacity: 0.4,
  },
  finalizeBtnPressed: {
    opacity: 0.85,
  },
  finalizeBtnText: {
    fontSize: 16,
    fontWeight: '700',
    color: theme.text.onBrand,
    fontVariant: ['tabular-nums'],
  },
});
