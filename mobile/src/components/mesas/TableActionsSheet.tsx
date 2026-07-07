// BottomSheetModal com acoes da mesa selecionada.
// Expoe open(table) e close() via forwardRef/useImperativeHandle.
// Acoes: primaria por estado (abrir/pedir conta/fechar), Ver conta (se ha
// sessao), QR Code e Cancelar. Fechar mesa: confirmacao via Alert.alert.

import React, {
  forwardRef,
  useCallback,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import { Alert, Pressable, StyleSheet, Text } from 'react-native';
import { BottomSheetModal, BottomSheetView } from '@gorhom/bottom-sheet';
import { DoorOpen, QrCode, Receipt, ReceiptX, X } from 'phosphor-react-native';
import { palette, semantic, theme } from '@/theme/colors';
import { api, ApiError } from '@/lib/api';
import { resolveTableState } from './TableCard';
import { renderBackdrop } from '@/components/pdv/sheetUtils';
import type { TableDto } from '@/types/tables';

export interface TableActionsSheetRef {
  open: (table: TableDto) => void;
  close: () => void;
}

interface TableActionsSheetProps {
  onSuccess: () => Promise<void>;
  onViewBill: (table: TableDto) => void;
  onShowQr: (table: TableDto) => void;
}

export const TableActionsSheet = forwardRef<TableActionsSheetRef, TableActionsSheetProps>(
  ({ onSuccess, onViewBill, onShowQr }, ref) => {
    const sheetRef = useRef<BottomSheetModal>(null);
    const [table, setTable] = useState<TableDto | null>(null);
    const [busy, setBusy] = useState(false);
    const [err, setErr] = useState<string | null>(null);

    useImperativeHandle(ref, () => ({
      open: (t: TableDto) => {
        setTable(t);
        setErr(null);
        setBusy(false);
        sheetRef.current?.present();
      },
      close: () => sheetRef.current?.dismiss(),
    }));

    const runAction = useCallback(
      async (ep: string) => {
        setBusy(true);
        setErr(null);
        try {
          await api.post(ep, {});
          sheetRef.current?.dismiss();
          await onSuccess();
        } catch (e) {
          setErr(e instanceof ApiError ? e.message : 'Erro ao executar acao.');
        } finally {
          setBusy(false);
        }
      },
      [onSuccess],
    );

    const handleAction = useCallback(async () => {
      if (!table || busy) return;
      const state = resolveTableState(table);

      if (state === 'free') {
        await runAction(`/tables/${table.id}/session/open`);
      } else if (state === 'open') {
        await runAction(`/tables/${table.id}/session/bill`);
      } else {
        // billing: confirmacao obrigatoria
        Alert.alert(
          'Fechar mesa?',
          `A sessao de ${table.label} sera encerrada. Essa acao nao pode ser desfeita.`,
          [
            { text: 'Cancelar', style: 'cancel' },
            {
              text: 'Fechar mesa',
              style: 'destructive',
              onPress: () => void runAction(`/tables/${table.id}/session/close`),
            },
          ],
        );
      }
    }, [table, busy, runAction]);

    const handleViewBill = useCallback(() => {
      if (!table) return;
      sheetRef.current?.dismiss();
      onViewBill(table);
    }, [table, onViewBill]);

    const handleShowQr = useCallback(() => {
      if (!table) return;
      sheetRef.current?.dismiss();
      onShowQr(table);
    }, [table, onShowQr]);

    if (!table) return null;

    const state = resolveTableState(table);

    type ActState = 'free' | 'open' | 'billing';
    const ACTION_META: Record<ActState, { label: string; bg: string; icon: React.ReactNode }> = {
      free: {
        label: 'Abrir mesa',
        bg: palette.primary[700],
        icon: <DoorOpen size={20} weight="fill" color={theme.text.onBrand} />,
      },
      open: {
        label: 'Pedir conta',
        bg: semantic.warning.DEFAULT,
        icon: <ReceiptX size={20} weight="fill" color={theme.text.onBrand} />,
      },
      billing: {
        label: 'Fechar mesa',
        bg: semantic.error.DEFAULT,
        icon: <X size={20} weight="bold" color={theme.text.onBrand} />,
      },
    };

    const meta = ACTION_META[state];

    return (
      <BottomSheetModal
        ref={sheetRef}
        enableDynamicSizing
        backdropComponent={renderBackdrop}
        backgroundStyle={styles.bg}
        handleIndicatorStyle={styles.handle}
      >
        <BottomSheetView style={styles.content}>
          <Text style={styles.title}>{table.label}</Text>
          <Text style={styles.sub}>
            {table.seats} {table.seats === 1 ? 'lugar' : 'lugares'}
          </Text>

          {err ? (
            <Text style={styles.errorText} accessibilityRole="alert">
              {err}
            </Text>
          ) : null}

          <Pressable
            onPress={() => void handleAction()}
            disabled={busy}
            android_ripple={{ color: 'rgba(255,255,255,0.2)' }}
            accessibilityRole="button"
            accessibilityLabel={meta.label}
            style={({ pressed }) => [
              styles.actionBtn,
              { backgroundColor: meta.bg },
              pressed && { opacity: 0.85 },
              busy && { opacity: 0.5 },
            ]}
          >
            {meta.icon}
            <Text style={styles.actionBtnText}>
              {busy ? 'Aguarde...' : meta.label}
            </Text>
          </Pressable>

          {/* Ver conta: so faz sentido com sessao ativa */}
          {state !== 'free' ? (
            <Pressable
              onPress={handleViewBill}
              android_ripple={{ color: 'rgba(0,0,0,0.06)' }}
              accessibilityRole="button"
              accessibilityLabel={`Ver conta da ${table.label}`}
              style={({ pressed }) => [styles.secondaryBtn, pressed && { opacity: 0.85 }]}
            >
              <Receipt size={20} weight="regular" color={theme.text.secondary} />
              <Text style={styles.secondaryBtnText}>Ver conta</Text>
            </Pressable>
          ) : null}

          <Pressable
            onPress={handleShowQr}
            android_ripple={{ color: 'rgba(0,0,0,0.06)' }}
            accessibilityRole="button"
            accessibilityLabel={`QR Code da ${table.label}`}
            style={({ pressed }) => [styles.secondaryBtn, pressed && { opacity: 0.85 }]}
          >
            <QrCode size={20} weight="regular" color={theme.text.secondary} />
            <Text style={styles.secondaryBtnText}>QR Code da mesa</Text>
          </Pressable>

          <Pressable
            onPress={() => sheetRef.current?.dismiss()}
            style={styles.cancelBtn}
            accessibilityRole="button"
            accessibilityLabel="Cancelar"
          >
            <Text style={styles.cancelBtnText}>Cancelar</Text>
          </Pressable>
        </BottomSheetView>
      </BottomSheetModal>
    );
  },
);

TableActionsSheet.displayName = 'TableActionsSheet';

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
    marginBottom: 24,
  },
  errorText: {
    fontSize: 13,
    color: semantic.error.DEFAULT,
    marginBottom: 12,
    textAlign: 'center',
  },
  actionBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
    minHeight: 56,
    borderRadius: 14,
    paddingHorizontal: 20,
    marginBottom: 12,
  },
  actionBtnText: {
    fontSize: 16,
    fontWeight: '700',
    color: theme.text.onBrand,
  },
  secondaryBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
    minHeight: 52,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: theme.border.light,
    paddingHorizontal: 20,
    marginBottom: 12,
  },
  secondaryBtnText: {
    fontSize: 15,
    fontWeight: '600',
    color: theme.text.secondary,
  },
  cancelBtn: {
    minHeight: 48,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: theme.border.light,
  },
  cancelBtnText: {
    fontSize: 15,
    fontWeight: '600',
    color: theme.text.secondary,
  },
});
