// Geracao/validacao de slug em kebab-case, espelhando a regex do backend
// (MenuLinkRequest: ^[a-z0-9]+(?:-[a-z0-9]+)*$, 2..60 chars).

export const SLUG_REGEX = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
export const SLUG_MAX = 60;

/**
 * Deriva um slug kebab-case a partir de texto livre: remove acentos, baixa a caixa,
 * troca nao-alfanumericos por hifen, colapsa hifens e apara as pontas. Limite 60.
 */
export function slugify(input: string): string {
  return input
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "") // remove diacriticos combinantes
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, SLUG_MAX)
    .replace(/-+$/g, ""); // reapara caso o corte de 60 tenha deixado hifen no fim
}

/** true se o slug bate a regex do backend e tem 2..60 chars. */
export function isValidSlug(slug: string): boolean {
  return slug.length >= 2 && slug.length <= SLUG_MAX && SLUG_REGEX.test(slug);
}
