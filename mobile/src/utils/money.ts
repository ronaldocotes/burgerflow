// Formatacao de dinheiro. O backend trafega CENTAVOS (Long); aqui so exibimos.
export function formatBRL(cents: number): string {
  return (cents / 100).toLocaleString('pt-BR', {
    style: 'currency',
    currency: 'BRL',
  });
}

// Converte um texto digitado em reais ("1.234,56" ou "1234,56" ou "12") para
// centavos (inteiro). Retorna null quando o texto nao e um numero valido.
// Usado nos campos de dinheiro do Caixa/PDV (teclado numerico, virgula decimal).
export function parseBRLToCents(input: string): number | null {
  const normalized = input.replace(/\./g, '').replace(',', '.').trim();
  if (normalized === '') return null;
  const value = parseFloat(normalized);
  return isFinite(value) ? Math.round(value * 100) : null;
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
