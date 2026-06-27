// BottomSheetModal com QR Code da mesa.
// URL: AppConfig.APP_URL/cardapio?table=<label> (encodeURIComponent).
// QRCode 220x220, fundo branco, cor primary-700 do token canônico.

import React, {
  forwardRef,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { BottomSheetModal, BottomSheetView } from '@gorhom/bottom-sheet';
import QRCode from 'react-native-qrcode-svg';
import { palette, theme } from '@/theme/colors';
import { AppConfig } from '@/config';
import { renderBackdrop } from '@/components/pdv/sheetUtils';
import type { TableDto } from '@/types/tables';

export interface TableQrSheetRef {
  open: (table: TableDto) => void;
  close: () => void;
}

export const TableQrSheet = forwardRef<TableQrSheetRef>((_, ref) => {
  const sheetRef = useRef<BottomSheetModal>(null);
  const [table, setTable] = useState<TableDto | null>(null);

  useImperativeHandle(ref, () => ({
    open: (t: TableDto) => {
      setTable(t);
      sheetRef.current?.present();
    },
    close: () => sheetRef.current?.dismiss(),
  }));

  if (!table) return null;

  const qrUrl = `${AppConfig.APP_URL}/cardapio?table=${encodeURIComponent(table.label)}`;

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
        <Text style={styles.sub}>Cardapio digital</Text>

        {/* QR Code sobre fundo branco com padding */}
        <View style={styles.qrWrapper}>
          <QRCode
            value={qrUrl}
            size={220}
            color={palette.primary[700]}
            backgroundColor="#ffffff"
          />
        </View>

        <Text style={styles.hint}>
          Aponte a camera para acessar o cardapio
        </Text>

        <Pressable
          onPress={() => sheetRef.current?.dismiss()}
          android_ripple={{ color: 'rgba(255,255,255,0.2)' }}
          accessibilityRole="button"
          accessibilityLabel="Fechar"
          style={({ pressed }) => [styles.closeBtn, pressed && { opacity: 0.8 }]}
        >
          <Text style={styles.closeBtnText}>Fechar</Text>
        </Pressable>
      </BottomSheetView>
    </BottomSheetModal>
  );
});

TableQrSheet.displayName = 'TableQrSheet';

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
    alignItems: 'center',
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
  qrWrapper: {
    padding: 16,
    backgroundColor: '#ffffff',
    borderRadius: 16,
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.07,
    shadowRadius: 4,
    marginBottom: 20,
  },
  hint: {
    fontSize: 13,
    color: theme.text.secondary,
    textAlign: 'center',
    marginBottom: 28,
  },
  closeBtn: {
    minHeight: 52,
    width: '100%',
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 14,
    backgroundColor: palette.primary[700],
  },
  closeBtnText: {
    fontSize: 16,
    fontWeight: '700',
    color: theme.text.onBrand,
  },
});
