// Modal de cancelamento com motivo OBRIGATÓRIO — multiplataforma.
// Substitui o Alert.prompt (que só existe no iOS; em Android não renderiza input).
// onConfirm deve LANÇAR em caso de falha: o modal mostra o erro e permanece aberto.

import React, { useState } from 'react';
import {
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  Text,
  TextInput,
  View,
} from 'react-native';
import { semantic, theme } from '@/theme/colors';
import type { KdsOrder } from '@/types/kds';

interface CancelModalProps {
  order: KdsOrder;
  onClose: () => void;
  onConfirm: (reason: string) => Promise<void>;
}

export function CancelModal({ order, onClose, onConfirm }: CancelModalProps) {
  const [reason, setReason] = useState('');
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function handleConfirm() {
    if (loading) return;
    if (!reason.trim()) {
      setErr('Informe o motivo do cancelamento.');
      return;
    }
    setLoading(true);
    setErr(null);
    try {
      await onConfirm(reason.trim());
      onClose();
    } catch {
      setErr('Não foi possível cancelar. Verifique a conexão e tente de novo.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <Modal visible transparent animationType="fade" onRequestClose={onClose}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={{
          flex: 1,
          backgroundColor: 'rgba(0,0,0,0.5)',
          alignItems: 'center',
          justifyContent: 'center',
          padding: 24,
        }}
      >
        <View
          style={{
            width: '100%',
            maxWidth: 420,
            borderRadius: 16,
            backgroundColor: theme.bg.primary,
            padding: 20,
          }}
        >
          <Text style={{ fontSize: 18, fontWeight: '700', color: theme.text.primary }}>
            Cancelar pedido #{order.orderNumber}
          </Text>
          <Text
            style={{
              fontSize: 14,
              color: theme.text.secondary,
              marginTop: 4,
              marginBottom: 12,
            }}
          >
            Esta ação não pode ser desfeita. Informe o motivo.
          </Text>
          <TextInput
            value={reason}
            onChangeText={setReason}
            placeholder="Ex.: cliente desistiu, ingrediente em falta…"
            placeholderTextColor={theme.text.muted}
            multiline
            autoFocus
            accessibilityLabel="Motivo do cancelamento"
            style={{
              minHeight: 80,
              textAlignVertical: 'top',
              borderWidth: 1,
              borderColor: theme.border.medium,
              borderRadius: 10,
              padding: 12,
              fontSize: 15,
              color: theme.text.primary,
              backgroundColor: theme.bg.secondary,
            }}
          />
          {err ? (
            <Text
              accessibilityRole="alert"
              style={{ marginTop: 8, fontSize: 13, color: semantic.error.DEFAULT }}
            >
              {err}
            </Text>
          ) : null}
          <View style={{ flexDirection: 'row', gap: 10, marginTop: 16 }}>
            <Pressable
              onPress={onClose}
              disabled={loading}
              accessibilityRole="button"
              accessibilityLabel="Voltar sem cancelar"
              style={({ pressed }) => ({
                flex: 1,
                minHeight: 48,
                borderRadius: 12,
                borderWidth: 1,
                borderColor: theme.border.medium,
                alignItems: 'center',
                justifyContent: 'center',
                opacity: pressed || loading ? 0.6 : 1,
              })}
            >
              <Text style={{ fontSize: 15, fontWeight: '600', color: theme.text.primary }}>
                Voltar
              </Text>
            </Pressable>
            <Pressable
              onPress={handleConfirm}
              disabled={loading}
              accessibilityRole="button"
              accessibilityLabel={`Confirmar cancelamento do pedido #${order.orderNumber}`}
              style={({ pressed }) => ({
                flex: 1,
                minHeight: 48,
                borderRadius: 12,
                backgroundColor: semantic.error.DEFAULT,
                alignItems: 'center',
                justifyContent: 'center',
                opacity: pressed || loading ? 0.7 : 1,
              })}
            >
              <Text style={{ fontSize: 15, fontWeight: '700', color: theme.text.onBrand }}>
                {loading ? 'Cancelando…' : 'Confirmar'}
              </Text>
            </Pressable>
          </View>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}
