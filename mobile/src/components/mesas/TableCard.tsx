// Card individual de mesa no board de Mesas.
// Pressable android_ripple, minimo 48dp em todos os alvos de toque.
// Status nunca so por cor: icone Phosphor + texto sempre presentes.
// Botao de acao (52dp) na base executa a acao diretamente sem abrir o sheet.

import React, { useState } from 'react';
import { Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import { Chair, QrCode, Receipt, Users } from 'phosphor-react-native';
import { palette, semantic, tableStatus, theme } from '@/theme/colors';
import { api, ApiError } from '@/lib/api';
import type { TableDto } from '@/types/tables';

// -- Helpers de estado --------------------------------------------------------

export type TableState = 'free' | 'open' | 'billing';

export function resolveTableState(table: TableDto): TableState {
  if (!table.session) return 'free';
  return table.session.status === 'BILLING' ? 'billing' : 'open';
}

// -- Icone de status (nunca so cor) -------------------------------------------

function StatusIcon({ state }: { state: TableState }) {
  if (state === 'free') {
    return <Chair size={18} weight="regular" color={palette.neutral[400]} />;
  }
  if (state === 'open') {
    return <Users size={18} weight="fill" color={theme.text.onBrand} />;
  }
  // billing
  return <Receipt size={18} weight="fill" color={semantic.warning.dark} />;
}

const STATUS_LABEL: Record<TableState, string> = {
  free: 'Livre',
  open: 'Aberta',
  billing: 'Conta pedida',
};

// -- TableCard ----------------------------------------------------------------

interface TableCardProps {
  table: TableDto;
  onCardPress: (table: TableDto) => void;
  onQrPress: (table: TableDto) => void;
  onActionSuccess: () => Promise<void>;
}

export function TableCard({
  table,
  onCardPress,
  onQrPress,
  onActionSuccess,
}: TableCardProps) {
  const state = resolveTableState(table);
  const [busy, setBusy] = useState(false);
  const seatsWord = table.seats === 1 ? 'lugar' : 'lugares';

  // Cores por estado (tokens canonicos, zero hex inline)
  const cardBg = state === 'open' ? tableStatus.open : theme.bg.primary;
  const cardBgFinal = state === 'billing' ? semantic.warning.light : cardBg;
  const cardBorderColor = state === 'billing' ? semantic.warning.DEFAULT : theme.border.light;
  const cardBorderWidth = state === 'billing' ? 2 : 1;
  const labelColor = state === 'open' ? theme.text.onBrand : theme.text.primary;
  const seatsColor = state === 'open' ? 'rgba(255,255,255,0.7)' : theme.text.secondary;
  const statusTextColor =
    state === 'free'
      ? palette.neutral[400]
      : state === 'open'
        ? theme.text.onBrand
        : semantic.warning.dark;
  const qrColor = state === 'open' ? 'rgba(255,255,255,0.6)' : palette.neutral[400];

  const ACTION_CONFIG: Record<TableState, { label: string }> = {
    free: { label: 'Abrir mesa' },
    open: { label: 'Pedir conta' },
    billing: { label: 'Fechar mesa' },
  };
  const { label: actionLabel } = ACTION_CONFIG[state];

  async function runAction() {
    setBusy(true);
    try {
      const ep =
        state === 'free'
          ? `/tables/${table.id}/session/open`
          : state === 'open'
            ? `/tables/${table.id}/session/bill`
            : `/tables/${table.id}/session/close`;
      await api.post(ep, {});
      await onActionSuccess();
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : 'Erro ao executar ação.';
      Alert.alert('Erro', msg);
    } finally {
      setBusy(false);
    }
  }

  // Acao do botao base: executa diretamente (sem abrir sheet).
  // Fechar mesa: confirmacao obrigatoria via Alert.alert.
  async function executeAction() {
    if (busy) return;
    if (state === 'billing') {
      Alert.alert(
        'Fechar mesa?',
        `A sessão de ${table.label} será encerrada. Essa ação não pode ser desfeita.`,
        [
          { text: 'Cancelar', style: 'cancel' },
          {
            text: 'Fechar mesa',
            style: 'destructive',
            onPress: () => void runAction(),
          },
        ],
      );
      return;
    }
    await runAction();
  }

  return (
    <View
      style={[
        styles.card,
        {
          backgroundColor: cardBgFinal,
          borderColor: cardBorderColor,
          borderWidth: cardBorderWidth,
        },
      ]}
      accessibilityLabel={`${table.label}, ${STATUS_LABEL[state]}, ${table.seats} ${seatsWord}`}
    >
      {/* Area principal: toque no card abre o sheet de acoes */}
      <Pressable
        onPress={() => onCardPress(table)}
        android_ripple={{ color: 'rgba(0,0,0,0.06)' }}
        style={styles.cardBody}
        accessibilityRole="button"
        accessibilityLabel={`${table.label}: ver acoes`}
      >
        <View style={styles.headerRow}>
          <Text style={[styles.label, { color: labelColor }]} numberOfLines={1}>
            {table.label}
          </Text>
          {/* Botao QR no canto superior direito, 32dp + hitSlop = 40dp efetivo */}
          <Pressable
            onPress={() => onQrPress(table)}
            android_ripple={{ color: 'rgba(0,0,0,0.1)', radius: 20 }}
            hitSlop={8}
            style={styles.qrBtn}
            accessibilityRole="button"
            accessibilityLabel={`QR Code da ${table.label}`}
          >
            <QrCode size={20} weight="regular" color={qrColor} />
          </Pressable>
        </View>

        <Text style={[styles.seats, { color: seatsColor }]}>
          {table.seats} {seatsWord}
        </Text>

        {/* Status: icone + texto -- nunca so cor */}
        <View style={styles.statusRow}>
          <StatusIcon state={state} />
          <Text
            style={[styles.statusText, { color: statusTextColor }]}
            accessibilityElementsHidden
          >
            {STATUS_LABEL[state]}
          </Text>
        </View>
      </Pressable>

      {/* Botao de acao -- 52dp, largura total, rodape do card */}
      <Pressable
        onPress={() => void executeAction()}
        disabled={busy}
        android_ripple={{ color: 'rgba(255,255,255,0.2)' }}
        accessibilityRole="button"
        accessibilityLabel={`${actionLabel} - ${table.label}`}
        style={({ pressed }) => [
          styles.actionBtn,
          state === 'open' && styles.actionBtnOutlineOpen,
          state === 'billing' && { backgroundColor: semantic.error.DEFAULT },
          state === 'free' && { backgroundColor: palette.primary[700] },
          pressed && { opacity: 0.8 },
          busy && { opacity: 0.5 },
        ]}
      >
        <Text
          style={[
            styles.actionBtnText,
            {
              color:
                state === 'open' ? semantic.warning.DEFAULT : theme.text.onBrand,
            },
          ]}
        >
          {busy ? 'Aguarde...' : actionLabel}
        </Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    flex: 1,
    borderRadius: 14,
    overflow: 'hidden',
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.07,
    shadowRadius: 4,
  },
  cardBody: {
    padding: 14,
    paddingBottom: 10,
    minHeight: 100,
  },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 4,
  },
  label: {
    fontSize: 18,
    fontWeight: '700',
    flex: 1,
    marginRight: 4,
  },
  qrBtn: {
    minWidth: 32,
    minHeight: 32,
    alignItems: 'center',
    justifyContent: 'center',
  },
  seats: {
    fontSize: 12,
    marginBottom: 10,
  },
  statusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
  },
  statusText: {
    fontSize: 13,
    fontWeight: '600',
  },
  actionBtn: {
    minHeight: 52,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 12,
  },
  // Pedir conta: outline sobre fundo primary-700 do card aberto
  actionBtnOutlineOpen: {
    backgroundColor: 'rgba(255,255,255,0.15)',
    borderTopWidth: 1,
    borderTopColor: 'rgba(255,255,255,0.2)',
  },
  actionBtnText: {
    fontSize: 14,
    fontWeight: '700',
  },
});
