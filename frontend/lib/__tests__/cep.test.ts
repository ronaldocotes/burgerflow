import { formatCep, cepDigits } from "@/lib/cep";

describe("formatCep", () => {
  it("aplica a mascara 00000-000", () => {
    expect(formatCep("68900000")).toBe("68900-000");
    expect(formatCep("68900")).toBe("68900");
    expect(formatCep("689")).toBe("689");
  });

  it("ignora nao-digitos e trunca em 8", () => {
    expect(formatCep("68.900-000abc")).toBe("68900-000");
    expect(formatCep("689000009999")).toBe("68900-000");
  });
});

describe("cepDigits", () => {
  it("retorna so os 8 digitos", () => {
    expect(cepDigits("68900-000")).toBe("68900000");
    expect(cepDigits("689000001234")).toBe("68900000");
  });
});
