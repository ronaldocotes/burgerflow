import React, { useEffect, useState } from 'react';

import { AtualizacaoModal } from './AtualizacaoModal';
import { checarAtualizacao, type AppRelease } from './updater';

/**
 * Gate de atualizacao in-app do app do motoboy. Uma unica checagem no boot:
 * consulta /public/app/latest e, se houver versao mais nova, sobrepoe o modal.
 *
 * - Silencioso: qualquer erro/sem-rede -> nao renderiza nada (checarAtualizacao
 *   ja engole a falha). Nunca trava o app.
 * - "Agora nao" apenas dispensa nesta sessao (obrigatoria=true nao mostra o botao).
 * - Renderizado como irmao do navigator; o Modal do RN se sobrepoe a navegacao.
 */
export function AppUpdateGate() {
  const [release, setRelease] = useState<AppRelease | null>(null);
  const [adiado, setAdiado] = useState(false);

  useEffect(() => {
    let vivo = true;
    checarAtualizacao().then((rel) => {
      if (vivo) setRelease(rel);
    });
    return () => {
      vivo = false;
    };
  }, []);

  if (!release || adiado) return null;

  return (
    <AtualizacaoModal release={release} onAdiar={() => setAdiado(true)} />
  );
}
