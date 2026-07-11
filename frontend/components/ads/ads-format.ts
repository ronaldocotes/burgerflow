// Formatacao de dinheiro do modulo Trafego Pago: SEMPRE a partir de centavos
// inteiros na moeda da conta — nunca float bruto (mesma regra da pagina 8.0/8.1).

export function fmtAdsMoney(cents: number, currency: string | null): string {
  const cur = currency ?? "BRL";
  const locale = cur === "BRL" ? "pt-BR" : "en-US";
  try {
    return (cents / 100).toLocaleString(locale, { style: "currency", currency: cur });
  } catch {
    return `${(cents / 100).toFixed(2)} ${cur}`;
  }
}
