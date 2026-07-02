import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { EntryPopupModal } from "@/components/cardapio/EntryPopupModal";
import type { Product } from "@/types/menu";

function prod(over: Partial<Product> = {}): Product {
  return {
    id: "p1",
    categoryId: "c1",
    sku: "s1",
    name: "X-Tudo",
    description: "",
    priceCents: 3290,
    costPriceCents: null,
    imageUrl: null,
    active: true,
    isAvailable: true,
    displayOrder: 0,
    preparationTimeMinutes: 0,
    isFeatured: false,
    promoPriceCents: null,
    promoStartsAt: null,
    promoEndsAt: null,
    effectivePriceCents: 3290,
    onPromo: false,
    createdAt: "",
    updatedAt: "",
    ...over,
  };
}

describe("EntryPopupModal", () => {
  it("mostra o titulo e o preco; clique no card seleciona o produto", async () => {
    const onSelectProduct = jest.fn();
    const onClose = jest.fn();
    render(
      <EntryPopupModal
        title="Destaques da casa"
        products={[prod()]}
        showPrices
        onSelectProduct={onSelectProduct}
        onClose={onClose}
      />,
    );
    expect(screen.getByRole("dialog", { name: "Destaques da casa" })).toBeInTheDocument();
    expect(screen.getByText(/32,90/)).toBeInTheDocument();
    await userEvent.click(screen.getByText("X-Tudo"));
    expect(onSelectProduct).toHaveBeenCalledTimes(1);
  });

  it("oculta o preco quando showPrices=false e usa titulo padrao", () => {
    render(
      <EntryPopupModal
        title={null}
        products={[prod()]}
        showPrices={false}
        onSelectProduct={() => {}}
        onClose={() => {}}
      />,
    );
    expect(screen.queryByText(/32,90/)).not.toBeInTheDocument();
    expect(screen.getByRole("dialog", { name: "Destaques" })).toBeInTheDocument();
  });

  it("botao fechar tem rotulo acessivel", async () => {
    const onClose = jest.fn();
    render(
      <EntryPopupModal
        title="X"
        products={[prod()]}
        showPrices
        onSelectProduct={() => {}}
        onClose={onClose}
      />,
    );
    await userEvent.click(screen.getByRole("button", { name: "Fechar destaques" }));
    expect(onClose).toHaveBeenCalled();
  });
});
