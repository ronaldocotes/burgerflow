import React from 'react';
import { View, Text, StyleSheet } from 'react-native';

// Stub M0 — tela real implementada em M1
export default function KdsScreen() {
  return (
    <View style={styles.container}>
      <Text style={styles.label}>Cozinha (KDS)</Text>
      <Text style={styles.sublabel}>Em breve — M1</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    backgroundColor: '#F8FAFC',
  },
  label: {
    fontSize: 20,
    fontWeight: '700',
    color: '#0F172A',
    marginBottom: 8,
  },
  sublabel: {
    fontSize: 14,
    color: '#475569',
  },
});
