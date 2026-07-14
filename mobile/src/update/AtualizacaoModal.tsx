import React, { useState } from 'react';
import {
  ActivityIndicator,
  Modal,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { CloudArrowDown, DownloadSimple } from 'phosphor-react-native';

import {
  baixarEInstalar,
  versionNameInstalado,
  type AppRelease,
} from './updater';

// Verde da marca (mesmo tom do splash/RootNavigator: #047857).
const VERDE = '#047857';

/**
 * Aviso de nova versao: baixa o APK (com progresso) e abre a instalacao. Se `obrigatoria`,
 * nao ha como adiar (sem botao "agora nao" e sem fechar pelo back).
 */
export function AtualizacaoModal({
  release,
  onAdiar,
}: {
  release: AppRelease;
  onAdiar?: () => void;
}) {
  const [baixando, setBaixando] = useState(false);
  const [frac, setFrac] = useState(0);
  const [erro, setErro] = useState('');

  const atualizar = async () => {
    setBaixando(true);
    setErro('');
    setFrac(0);
    try {
      await baixarEInstalar(release, setFrac);
    } catch (e) {
      setErro(e instanceof Error ? e.message : 'Nao foi possivel atualizar.');
    } finally {
      // Se instalou, o app e substituido antes disto; se cancelou, volta o botao.
      setBaixando(false);
    }
  };

  const pct = Math.round(frac * 100);
  const podeAdiar = !release.obrigatoria && !!onAdiar && !baixando;

  return (
    <Modal
      visible
      transparent
      animationType="fade"
      onRequestClose={podeAdiar ? onAdiar : () => {}}
    >
      <View style={styles.fundo}>
        <View style={styles.card}>
          <CloudArrowDown
            size={42}
            color={VERDE}
            weight="duotone"
            style={styles.icone}
          />
          <Text style={styles.titulo}>Atualizacao disponivel</Text>
          <Text style={styles.versao}>
            Versao {release.versionName} · voce tem a {versionNameInstalado()}
          </Text>
          {!!release.notas && <Text style={styles.notas}>{release.notas}</Text>}
          {release.obrigatoria && (
            <Text style={styles.obrig}>
              Esta atualizacao e obrigatoria para continuar usando o app.
            </Text>
          )}
          {!!erro && <Text style={styles.erro}>{erro}</Text>}

          {baixando ? (
            <View style={styles.progresso}>
              <ActivityIndicator color={VERDE} />
              <Text style={styles.progressoTxt}>Baixando… {pct}%</Text>
            </View>
          ) : (
            <Pressable style={styles.btn} onPress={atualizar}>
              <DownloadSimple size={18} color="#fff" weight="bold" />
              <Text style={styles.btnTxt}>Atualizar agora</Text>
            </Pressable>
          )}

          {podeAdiar && (
            <Pressable style={styles.btnSec} onPress={onAdiar}>
              <Text style={styles.btnSecTxt}>Agora nao</Text>
            </Pressable>
          )}
          <Text style={styles.rodape}>
            O sistema vai pedir para confirmar a instalacao.
          </Text>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  fundo: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.55)',
    justifyContent: 'center',
    padding: 24,
  },
  card: { backgroundColor: '#fff', borderRadius: 16, padding: 22 },
  icone: { alignSelf: 'center', marginBottom: 6 },
  titulo: {
    fontSize: 19,
    fontWeight: '800',
    color: '#0F172A',
    textAlign: 'center',
  },
  versao: { fontSize: 13, color: '#557', textAlign: 'center', marginTop: 4 },
  notas: { fontSize: 14, color: '#334', marginTop: 14, lineHeight: 20 },
  obrig: {
    fontSize: 13,
    color: '#9a6b00',
    backgroundColor: '#fdf0d5',
    borderRadius: 8,
    padding: 10,
    marginTop: 12,
  },
  erro: { color: '#b00020', marginTop: 12, textAlign: 'center' },
  progresso: {
    flexDirection: 'row',
    gap: 10,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 18,
    paddingVertical: 12,
  },
  progressoTxt: { color: VERDE, fontWeight: '700', fontSize: 15 },
  btn: {
    flexDirection: 'row',
    gap: 8,
    backgroundColor: VERDE,
    borderRadius: 10,
    paddingVertical: 13,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 18,
  },
  btnTxt: { color: '#fff', fontWeight: '800', fontSize: 15 },
  btnSec: { paddingVertical: 11, alignItems: 'center', marginTop: 6 },
  btnSecTxt: { color: '#557', fontWeight: '700' },
  rodape: { color: '#9aa', fontSize: 11, textAlign: 'center', marginTop: 12 },
});
