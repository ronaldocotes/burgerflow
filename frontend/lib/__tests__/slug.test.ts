import { slugify, isValidSlug } from "@/lib/slug";

describe("slugify", () => {
  it("gera kebab-case removendo acentos e maiusculas", () => {
    expect(slugify("Delivery e Retirada")).toBe("delivery-e-retirada");
    expect(slugify("Vitrine da Parede")).toBe("vitrine-da-parede");
    expect(slugify("Mesa 4")).toBe("mesa-4");
    expect(slugify("Balcão Ção")).toBe("balcao-cao");
  });

  it("colapsa separadores e apara as pontas", () => {
    expect(slugify("  --Olá!!  Mundo--  ")).toBe("ola-mundo");
  });

  it("respeita o limite de 60 sem deixar hifen no fim", () => {
    const out = slugify("a".repeat(70));
    expect(out.length).toBeLessThanOrEqual(60);
    expect(out.endsWith("-")).toBe(false);
  });
});

describe("isValidSlug", () => {
  it("aceita slugs validos", () => {
    expect(isValidSlug("pedir")).toBe(true);
    expect(isValidSlug("mesa-4")).toBe(true);
    expect(isValidSlug("a1")).toBe(true);
  });

  it("rejeita invalidos (curto demais, maiusculo, hifen na ponta, espaco)", () => {
    expect(isValidSlug("a")).toBe(false);
    expect(isValidSlug("Mesa-4")).toBe(false);
    expect(isValidSlug("-mesa")).toBe(false);
    expect(isValidSlug("mesa-")).toBe(false);
    expect(isValidSlug("mesa 4")).toBe(false);
    expect(isValidSlug("a".repeat(61))).toBe(false);
  });
});
