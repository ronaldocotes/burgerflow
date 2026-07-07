// BottomSheetModal com a conta (itens + total) da sessao ativa da mesa.
// Contrato: GET /orders?from=<session.openedAt>&size=100 -> Page<OrderResponse>,
// filtrado no cliente por tableNumber === table.label e status !== CANCELLED
// (o backend nao expoe endpoint de pedidos por sessao; ver TableController).
// Valores SEMPRE em centavos -> exibicao via formatBRL.

import React, {
  forwardRef,
  useCallback,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from 'react-native';
import { BottomSheetModal, BottomSheetScrollView } from '@gorhom/bottom-sheet';
import { Receipt } from 'phosphor-react-native';
import { palette, semantic, theme } from '@/theme/colors';
import { api, ApiError } from '@/lib/api';
import { formatBRL } from '@/utils/money';
import { renderBackdrop } from '@/components/pdv/sheetUtils';
import type { PageResponse, SessionOrder, TableDto } from '@/types/tables';

export interface TableBillSheetRef {
  open: (table: TableDto) => void;
  close: () => void;
}

type BillState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'ready'; orders: SessionOrder[]; totalCents: number };

function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('pt-BR', {
    hour: '2-digit',
    minute: '2-digit',
  });
}

export const TableBillSheet = forwardRef<TableBillSheetRef>((_, ref) => {
  const sheetRef = useRef<BottomSheetModal>(null);
  const [table, setTable] = useState<TableDto | null>(null);
  const [bill, setBill] = useState<BillState>({ kind: 'loading' });

  const load = useCallback(async (t: TableDto) => {
    setBill({ kind: 'loading' });
    if (!t.session) {
      // Mesa livre: nao ha sessao -> conta vazia.
      setBill({ kind: 'ready', orders: [], totalCents: 0 });
      return;
    }
    try {
      const from = encodeURIComponent(t.session.openedAt);
      const page = await api.get<PageResponse<SessionOrder>>(
        `/orders?from=${from}&size=100`,
      );
      const orders = page.content.filter(
        (o) => o.tableNumber === t.label && o.status !== 'CANCELLED',
      );
      const totalCents = orders.reduce((sum, o) => sum + o.totalCents, 0);
      setBill({ kind: 'ready', orders, totalCents });
    } catch (e) {
      const message =
        e instanceof ApiError && e.status === 403
          ? 'Seu perfil nao tem permissao para ver a conta.'
          : 'Nao foi possivel carregar a conta.';
      setBill({ kind: 'error', message });
    }
  }, []);

  useImperativeHandle(ref, () => ({
    open: (t: TableDto) => {
      setTable(t);
      sheetRef.current?.present();
      void load(t);
    },
    close: () => sheetRef.current?.dismiss(),
  }));

  if (!table) return null;

  const session = table.session;

  return (
    <BottomSheetModal
      ref={sheetRef}
      snapPoints={['60%', '90%']}
      enableDynamicSizing={false}
      backdropComponent={renderBackdrop}
      backgroundStyle={styles.bg}
      handleIndicatorStyle={styles.handle}
    >
      <BottomSheetScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title}>Conta - {table.label}</Text>
        {session ? (
          <Text style={styles.sub}>
            Aberta as {formatTime(session.openedAt)}
            {session.status === 'BILLING' ? ' - conta pedida' : ''}
          </Text>
        ) : (
          <Text style={styles.sub}>Mesa livre</Text>
        )}

        {bill.kind === 'loading' && (
          <View style={styles.centered} accessibilityLabel="Carregando conta">
            <ActivityIndicator size="large" color={palette.primary[700]} />
          </View>
        )}

        {bill.kind === 'error' && (
          <View style={styles.centered}>
            <Text style={styles.errorText} accessibilityRole="alert">
              {bill.message}
            </Text>
            <Pressable
              onPress={() => void load(table)}
              android_ripple={{ color: 'rgba(0,0,0,0.06)' }}
              style={styles.retryBtn}
              accessibilityRole="button"
              accessibilityLabel="Tentar novamente"
            >
              <Text style={styles.retryBtnText}>Tentar novamente</Text>
            </Pressable>
          </View>
        )}

        {bill.kind === 'ready' && bill.orders.length === 0 && (
          <View style={styles.centered}>
            <Receipt size={40} weight="regular" color={palette.neutral[400]} />
            <Text style={styles.emptyText}>Nenhum pedido lancado nesta mesa.</Text>
          </View>
        )}

        {bill.kind === 'ready' && bill.orders.length > 0 && (
          <View accessibilityLabel={`Conta da ${table.label}`}>
            {bill.orders.map((order) => (
              <View key={order.id} style={styles.orderBlock}>
                <Text style={styles.orderHeader}>
                  Pedido {order.orderNumber} - {formatTime(order.createdAt)}
                </Text>
                {order.items.map((item) => (
                  <View key={item.id} style={styles.itemRow}>
                    <Text style={styles.itemName} numberOfLines={2}>
                      {item.quantity}x {item.productName}
                    </Text>
                    <Text style={styles.itemPrice}>
                      {formatBRL(item.totalPriceCents)}
                    </Text>
                  </View>
                ))}
              </View>
            ))}

            <View style={styles.totalRow}>
              <Text style={styles.totalLabel}>Total</Text>
              <Text
                style={styles.totalValue}
                accessibilityLabel={`Total ${formatBRL(bill.totalCents)}`}
              >
                {formatBRL(bill.totalCents)}
              </Text>
            </View>
          </View>
        )}

        <Pressable
          onPress={() => sheetRef.current?.dismiss()}
          android_ripple={{ color: 'rgba(255,255,255,0.2)' }}
          accessibilityRole="button"
          accessibilityLabel="Fechar"
          style={({ pressed }) => [styles.closeBtn, pressed && { opacity: 0.8 }]}
        >
          <Text style={styles.closeBtnText}>Fechar</Text>
        </Pressable>
      </BottomSheetScrollView>
    </BottomSheetModal>
  );
});

TableBillSheet.displayName = 'TableBillSheet';

const styles = StyleSheet.create({
  bg: {
    backgroundColor: theme.bg.primary,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
  },
  handle: {
    backgroundColor: theme.border.medium,
    width: 40,
  },
  content: {
    padding: 24,
    paddingBottom: 40,
  },
  title: {
    fontSize: 22,
    fontWeight: '800',
    color: theme.text.primary,
    marginBottom: 4,
  },
  sub: {
    fontSize: 14,
    color: theme.text.secondary,
    marginBottom: 20,
  },
  centered: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 32,
    gap: 12,
  },
  errorText: {
    fontSize: 14,
    color: semantic.error.DEFAULT,
    textAlign: 'center',
  },
  retryBtn: {
    minHeight: 48,
    paddingHorizontal: 24,
    paddingVertical: 12,
    backgroundColor: palette.primary[700],
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  retryBtnText: {
    fontSize: 15,
    fontWeight: '700',
    color: theme.text.onBrand,
  },
  emptyText: {
    fontSize: 14,
    color: theme.text.secondary,
    textAlign: 'center',
  },
  orderBlock: {
    marginBottom: 16,
  },
  orderHeader: {
    fontSize: 12,
    fontWeight: '700',
    color: theme.text.muted,
    textTransform: 'uppercase',
    marginBottom: 8,
  },
  itemRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    justifyContent: 'space-between',
    gap: 12,
    paddingVertical: 6,
    borderBottomWidth: 1,
    borderBottomColor: theme.border.light,
  },
  itemName: {
    flex: 1,
    fontSize: 14,
    color: theme.text.primary,
  },
  itemPrice: {
    fontSize: 14,
    fontWeight: '600',
    color: theme.text.primary,
  },
  totalRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingTop: 14,
    marginBottom: 8,
  },
  totalLabel: {
    fontSize: 16,
    fontWeight: '800',
    color: theme.text.primary,
  },
  totalValue: {
    fontSize: 18,
    fontWeight: '800',
    color: palette.primary[700],
  },
  closeBtn: {
    minHeight: 52,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 14,
    borderWidth: 1,
    borderColor: theme.border.light,
    marginTop: 8,
  },
  closeBtnText: {
    fontSize: 15,
    fontWeight: '600',
    color: theme.text.secondary,
  },
});
