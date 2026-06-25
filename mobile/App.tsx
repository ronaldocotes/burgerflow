import React from 'react';
import {
  SafeAreaView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';

export default function App() {
  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="dark-content" backgroundColor={styles.safeArea.backgroundColor} />
      <View style={styles.container}>
        <Text style={styles.eyebrow}>MenuFlow</Text>
        <Text style={styles.title}>Operacao mobile em preparo</Text>
        <Text style={styles.body}>
          Base React Native atualizada e pronta para receber os fluxos reais de
          PDV, KDS e delivery sem imports fantasmas.
        </Text>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#F8FAFC',
  },
  container: {
    flex: 1,
    justifyContent: 'center',
    paddingHorizontal: 24,
  },
  eyebrow: {
    color: '#047857',
    fontSize: 14,
    fontWeight: '700',
    marginBottom: 12,
    textTransform: 'uppercase',
  },
  title: {
    color: '#0F172A',
    fontSize: 30,
    fontWeight: '800',
    marginBottom: 12,
  },
  body: {
    color: '#475569',
    fontSize: 16,
    lineHeight: 24,
  },
});
