import { expect, test } from "@playwright/test";

const tenant = process.env.E2E_TENANT ?? "demo";
const email = process.env.E2E_EMAIL ?? "admin@demo.com";
const password = process.env.E2E_PASSWORD ?? "Demo@1234";

async function login(page: import("@playwright/test").Page) {
  await page.goto("/login");
  await page.getByLabel("Restaurante").fill(tenant);
  await page.getByLabel("E-mail").fill(email);
  await page.getByLabel("Senha").fill(password);
  await page.getByRole("button", { name: /entrar/i }).click();
  await expect(page).toHaveURL(/\/pdv/);
}

test.describe("MenuFlow fluxos críticos", () => {
  test("login autentica e abre o PDV", async ({ page }) => {
    await login(page);
    await expect(page.getByRole("heading", { name: "Carrinho" })).toBeVisible();
    await expect(page.getByLabel("Buscar produto")).toBeVisible();
  });

  test("PDV carrega produtos e permite iniciar um pedido", async ({ page }) => {
    await login(page);
    await page.goto("/pdv");
    await page.getByLabel("Buscar produto").fill("Combo Classic");
    await page.getByRole("button", { name: /Combo Classic/i }).first().click();
    await expect(page.getByRole("heading", { name: "Carrinho" })).toBeVisible();
    await expect(page.getByText("Combo Classic").first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Finalizar" })).toBeEnabled();
  });

  test("pedido no PDV chega até confirmação de pagamento", async ({ page }) => {
    await login(page);
    await page.goto("/pdv");
    await page.getByLabel("Buscar produto").fill("Smash Simples");
    await page.getByRole("button", { name: /Smash Simples/i }).first().click();
    await page.getByRole("button", { name: "Finalizar" }).click();
    await expect(page.getByRole("dialog", { name: "Pagamento" })).toBeVisible();
    await page.getByRole("button", { name: "Pix" }).click();
    await expect(page.getByRole("button", { name: /Confirmar pedido/i })).toBeEnabled();
  });

  test("cardápio público exibe categorias e produtos", async ({ page }) => {
    await page.goto("/cardapio");
    await expect(page.getByText("Cardápio").first()).toBeVisible();
    await expect(page.getByRole("button", { name: /Hambúrgueres Artesanais/i })).toBeVisible();
    await expect(page.getByText("MenuFlow Classic").first()).toBeVisible();
  });

  test("KDS abre e não mostra tela quebrada", async ({ page }) => {
    await login(page);
    await page.goto("/kds");
    await expect(page.getByText(/Cozinha|KDS|Reconectando|Nenhum pedido/i).first()).toBeVisible();
  });

  test("mesas abre o painel operacional", async ({ page }) => {
    await login(page);
    await page.goto("/mesas");
    await expect(page.getByRole("heading", { name: /Mesas/i })).toBeVisible();
    await expect(page.getByText(/Livre|Ocupada|Fechar/i).first()).toBeVisible();
  });

  test("admin de cardápio lista produtos, categorias e insumos", async ({ page }) => {
    await login(page);
    await page.goto("/admin/cardapio");
    await expect(page.getByRole("heading", { name: "Administração do cardápio" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Produtos" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Categorias" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Insumos" })).toBeVisible();
  });
});
