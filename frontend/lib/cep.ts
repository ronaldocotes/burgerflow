// Helpers de CEP compartilhados: mascara 00000-000 e busca ViaCEP.
// Extraido de components/cardapio/DeliveryAddressForm.tsx para reuso em Minha Loja.

export interface ViaCepAddress {
  street: string;
  neighborhood: string;
  city: string;
  uf: string;
}

interface ViaCepResult {
  logradouro?: string;
  bairro?: string;
  localidade?: string;
  uf?: string;
  erro?: boolean;
}

/** So os digitos do CEP (max 8). */
export function cepDigits(raw: string): string {
  return raw.replace(/\D/g, "").slice(0, 8);
}

/** Aplica a mascara visual 00000-000. */
export function formatCep(raw: string): string {
  const digits = cepDigits(raw);
  if (digits.length > 5) return digits.slice(0, 5) + "-" + digits.slice(5);
  return digits;
}

/**
 * Consulta o ViaCEP. Lanca "not_found" quando o CEP nao existe e propaga AbortError
 * quando cancelado (o chamador deve ignorar). Nunca preenche lat/lng (ViaCEP nao tem).
 */
export async function lookupCep(
  digits: string,
  signal?: AbortSignal,
): Promise<ViaCepAddress> {
  const res = await fetch(`https://viacep.com.br/ws/${digits}/json/`, { signal });
  if (!res.ok) throw new Error("not_found");
  const data = (await res.json()) as ViaCepResult;
  if (data.erro) throw new Error("not_found");
  return {
    street: data.logradouro ?? "",
    neighborhood: data.bairro ?? "",
    city: data.localidade ?? "",
    uf: (data.uf ?? "").toUpperCase(),
  };
}
