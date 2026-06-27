// Formatacao de dinheiro. O backend trafega CENTAVOS (Long); aqui so exibimos.
export function formatBRL(cents: number): string {
  return (cents / 100).toLocaleString('pt-BR', {
    style: 'currency',
    currency: 'BRL',
  });
}

// UUID v4. react-native-get-random-values (carregado em index.js) polyfilla
// crypto.getRandomValues; aqui usamos Math.random por simplicidade do Idempotency-Key
// gerado no cliente (colisao desprezivel para o volume de um restaurante).
export function uuid(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
