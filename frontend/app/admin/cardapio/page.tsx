"use client";

import { ChangeEvent, Dispatch, FormEvent, ReactNode, SetStateAction, useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { CheckCircle, ImagePlus, Layers3, Package, Pencil, Plus, RefreshCw, Save, Trash2, Wheat, X, XCircle, type LucideIcon } from "lucide-react";
import { api, ApiError } from "@/lib/api";
import { getToken } from "@/lib/auth";
import { Category, Ingredient, Page, Product, ProductAvailability, formatBRL } from "@/types/menu";

type Tab = "products" | "categories" | "ingredients";
type Notice = { type: "success" | "error"; message: string } | null;

const CHANNELS = [
  { value: "COUNTER", label: "Balcão" },
  { value: "DINE_IN", label: "Mesa" },
  { value: "DELIVERY", label: "Delivery" },
  { value: "ONLINE", label: "Cardápio digital" },
];

const EMPTY_PRODUCT = {
  id: "",
  categoryId: "",
  sku: "",
  name: "",
  description: "",
  priceCents: 0,
  costPriceCents: 0,
  imageUrl: "",
  isAvailable: true,
  displayOrder: 0,
  preparationTimeMinutes: 10,
  isFeatured: false,
  promoPriceCents: 0,
};

const EMPTY_CATEGORY = {
  id: "",
  name: "",
  description: "",
  displayOrder: 0,
  colorCode: "#047857",
  iconUrl: "",
};

const EMPTY_INGREDIENT = {
  id: "",
  name: "",
  description: "",
  unit: "UNIT",
  unitCostCents: 0,
  stockQuantity: 0,
  minStock: 0,
  isAllergen: false,
};

function centsInput(value: number): string {
  return (value / 100).toFixed(2);
}

function parseCents(value: string): number {
  const normalized = value.replace(/\./g, "").replace(",", ".");
  return Math.round((Number(normalized) || 0) * 100);
}

function selectedChannels(current: string[], value: string, checked: boolean): string[] {
  const next = new Set(current);
  if (checked) next.add(value);
  else next.delete(value);
  return Array.from(next);
}

export default function AdminCardapioPage() {
  const router = useRouter();
  const [tab, setTab] = useState<Tab>("products");
  const [notice, setNotice] = useState<Notice>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  const [categories, setCategories] = useState<Category[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [ingredients, setIngredients] = useState<Ingredient[]>([]);

  const [productForm, setProductForm] = useState(EMPTY_PRODUCT);
  const [categoryForm, setCategoryForm] = useState(EMPTY_CATEGORY);
  const [ingredientForm, setIngredientForm] = useState(EMPTY_INGREDIENT);
  const [availability, setAvailability] = useState<string[]>([]);
  const [uploading, setUploading] = useState(false);
  const [mobileFormOpen, setMobileFormOpen] = useState(false);
  const [isXl, setIsXl] = useState(false);

  useEffect(() => {
    const mq = window.matchMedia("(min-width: 1280px)");
    setIsXl(mq.matches);
    const handler = (e: MediaQueryListEvent) => setIsXl(e.matches);
    mq.addEventListener("change", handler);
    return () => mq.removeEventListener("change", handler);
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setNotice(null);
    try {
      const [catRes, prodRes, ingRes] = await Promise.all([
        api.get<Page<Category>>("/categories?size=200"),
        api.get<Page<Product>>("/products?size=300"),
        api.get<Ingredient[]>("/ingredients"),
      ]);
      setCategories(catRes.content);
      setProducts(prodRes.content);
      setIngredients(ingRes);
      setProductForm((prev) => ({
        ...prev,
        categoryId: prev.categoryId || catRes.content[0]?.id || "",
      }));
    } catch (err) {
      setNotice({ type: "error", message: err instanceof Error ? err.message : "Erro ao carregar dados." });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!getToken()) {
      router.replace("/login");
      return;
    }
    queueMicrotask(() => {
      void load();
    });
  }, [load, router]);

  const activeProducts = useMemo(() => products.filter((p) => p.active), [products]);
  const activeCategories = useMemo(() => categories.filter((c) => c.active), [categories]);
  const activeIngredients = useMemo(() => ingredients.filter((i) => i.active), [ingredients]);

  function resetProduct() {
    setProductForm({ ...EMPTY_PRODUCT, categoryId: activeCategories[0]?.id || "" });
    setAvailability([]);
  }

  async function editProduct(product: Product) {
    setTab("products");
    setProductForm({
      id: product.id,
      categoryId: product.categoryId,
      sku: product.sku,
      name: product.name,
      description: product.description,
      priceCents: product.priceCents,
      costPriceCents: product.costPriceCents ?? 0,
      imageUrl: product.imageUrl ?? "",
      isAvailable: product.isAvailable,
      displayOrder: product.displayOrder,
      preparationTimeMinutes: product.preparationTimeMinutes,
      isFeatured: product.isFeatured,
      promoPriceCents: product.promoPriceCents ?? 0,
    });
    try {
      const current = await api.get<ProductAvailability>(`/products/${product.id}/availability`);
      setAvailability(current.channels);
    } catch {
      setAvailability([]);
    }
    if (!isXl) setMobileFormOpen(true);
  }

  async function saveProduct(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!productForm.categoryId) {
      setNotice({ type: "error", message: "Crie uma categoria antes de cadastrar produtos." });
      return;
    }
    setSaving(true);
    try {
      const body = {
        categoryId: productForm.categoryId,
        sku: productForm.sku,
        name: productForm.name,
        description: productForm.description,
        priceCents: productForm.priceCents,
        costPriceCents: productForm.costPriceCents || null,
        imageUrl: productForm.imageUrl || null,
        isAvailable: productForm.isAvailable,
        displayOrder: productForm.displayOrder,
        preparationTimeMinutes: productForm.preparationTimeMinutes,
        isFeatured: productForm.isFeatured,
        promoPriceCents: productForm.promoPriceCents || null,
      };
      const saved = productForm.id
        ? await api.put<Product>(`/products/${productForm.id}`, body)
        : await api.post<Product>("/products", body, { "Idempotency-Key": crypto.randomUUID() });
      await api.put<ProductAvailability>(`/products/${saved.id}/availability`, { channels: availability, windows: [] });
      setNotice({ type: "success", message: "Produto salvo." });
      setMobileFormOpen(false);
      resetProduct();
      await load();
    } catch (err) {
      setNotice({ type: "error", message: err instanceof ApiError ? err.message : "Erro ao salvar produto." });
    } finally {
      setSaving(false);
    }
  }

  async function saveCategory(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    try {
      const body = {
        name: categoryForm.name,
        description: categoryForm.description,
        displayOrder: categoryForm.displayOrder,
        colorCode: categoryForm.colorCode || null,
        iconUrl: categoryForm.iconUrl || null,
      };
      categoryForm.id
        ? await api.put<Category>(`/categories/${categoryForm.id}`, body)
        : await api.post<Category>("/categories", body);
      setNotice({ type: "success", message: "Categoria salva." });
      setCategoryForm(EMPTY_CATEGORY);
      await load();
    } catch (err) {
      setNotice({ type: "error", message: err instanceof ApiError ? err.message : "Erro ao salvar categoria." });
    } finally {
      setSaving(false);
    }
  }

  async function saveIngredient(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    try {
      const body = {
        name: ingredientForm.name,
        description: ingredientForm.description,
        unit: ingredientForm.unit,
        unitCostCents: ingredientForm.unitCostCents,
        stockQuantity: ingredientForm.stockQuantity,
        minStock: ingredientForm.minStock,
        isAllergen: ingredientForm.isAllergen,
      };
      ingredientForm.id
        ? await api.put<Ingredient>(`/ingredients/${ingredientForm.id}`, body)
        : await api.post<Ingredient>("/ingredients", body);
      setNotice({ type: "success", message: "Insumo salvo." });
      setIngredientForm(EMPTY_INGREDIENT);
      await load();
    } catch (err) {
      setNotice({ type: "error", message: err instanceof ApiError ? err.message : "Erro ao salvar insumo." });
    } finally {
      setSaving(false);
    }
  }

  async function remove(path: string, label: string) {
    if (!window.confirm(`Remover ${label}?`)) return;
    setSaving(true);
    try {
      await api.del(path);
      setNotice({ type: "success", message: `${label} removido.` });
      await load();
    } catch (err) {
      setNotice({ type: "error", message: err instanceof ApiError ? err.message : `Erro ao remover ${label}.` });
    } finally {
      setSaving(false);
    }
  }

  async function uploadImage(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    setUploading(true);
    try {
      const form = new FormData();
      form.append("file", file);
      const token = getToken() ?? "";
      const res = await fetch("/api/uploads/menu", {
        method: "POST",
        headers: token ? { Authorization: `Bearer ${token}` } : undefined,
        body: form,
      });
      const data = (await res.json()) as { url?: string; message?: string };
      if (!res.ok || !data.url) throw new Error(data.message ?? "Erro ao enviar imagem.");
      setProductForm((prev) => ({ ...prev, imageUrl: data.url ?? "" }));
      setNotice({ type: "success", message: "Imagem enviada." });
    } catch (err) {
      setNotice({ type: "error", message: err instanceof Error ? err.message : "Erro ao enviar imagem." });
    } finally {
      setUploading(false);
      event.target.value = "";
    }
  }

  return (
    <div className="min-h-screen bg-bg-secondary">
      <main className="mx-auto flex w-full max-w-7xl flex-col gap-5 px-4 py-6">
        <header className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl font-bold text-text-primary">Administração do cardápio</h1>
            <p className="mt-1 text-sm text-text-secondary">
              Produtos, categorias, insumos, imagens e canais de venda.
            </p>
          </div>
          <button className="btn-secondary inline-flex items-center gap-2" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
            Atualizar
          </button>
        </header>

        {notice && (
          <div
            role={notice.type === "error" ? "alert" : "status"}
            className={[
              "rounded-lg px-4 py-3 text-sm font-medium",
              notice.type === "success" ? "bg-success/10 text-success" : "bg-error/10 text-error",
            ].join(" ")}
          >
            {notice.message}
          </div>
        )}

        <section className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Metric icon={Package} label="Produtos ativos" value={activeProducts.length} />
          <Metric icon={Layers3} label="Categorias" value={activeCategories.length} />
          <Metric icon={Wheat} label="Insumos" value={activeIngredients.length} />
          <Metric icon={ImagePlus} label="Com imagem" value={activeProducts.filter((p) => p.imageUrl).length} />
        </section>

        <div role="tablist" aria-label="Seções do cardápio" className="flex gap-2 overflow-x-auto border-b border-border-light">
          <TabButton active={tab === "products"} onClick={() => setTab("products")} icon={Package} label="Produtos" />
          <TabButton active={tab === "categories"} onClick={() => setTab("categories")} icon={Layers3} label="Categorias" />
          <TabButton active={tab === "ingredients"} onClick={() => setTab("ingredients")} icon={Wheat} label="Insumos" />
        </div>

        {loading ? (
          <div className="rounded-lg bg-bg-primary p-6 text-sm text-text-secondary shadow-card">Carregando dados...</div>
        ) : (
          <>
            {tab === "products" && (
              <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]">
                <section className="overflow-hidden rounded-lg bg-bg-primary shadow-card">
                  <TableHeader title="Produtos" actionLabel="Novo produto" onAction={() => { resetProduct(); if (!isXl) setMobileFormOpen(true); }} />
                  <div className="overflow-x-auto">
                    <table className="min-w-full text-sm">
                      <thead className="bg-bg-secondary text-left text-xs uppercase text-text-muted">
                        <tr>
                          <th scope="col" className="px-4 py-3">Produto</th>
                          <th scope="col" className="px-4 py-3">Categoria</th>
                          <th scope="col" className="px-4 py-3">Preço</th>
                          <th scope="col" className="px-4 py-3">Status</th>
                          <th scope="col" className="px-4 py-3 text-right">Ações</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-border-light">
                        {activeProducts.length === 0 && (
                          <tr>
                            <td colSpan={5} className="px-4 py-8 text-center text-sm text-text-muted">
                              Nenhum produto cadastrado. Clique em &ldquo;Novo produto&rdquo; para começar.
                            </td>
                          </tr>
                        )}
                        {activeProducts.map((product) => (
                          <tr key={product.id} className="hover:bg-bg-secondary/70">
                            <td className="px-4 py-3">
                              <button className="text-left font-semibold text-text-primary hover:text-primary-700 hover:underline" onClick={() => void editProduct(product)}>
                                {product.name}
                              </button>
                              <p className="text-xs text-text-muted">{product.sku}</p>
                            </td>
                            <td className="px-4 py-3 text-text-secondary">
                              {categories.find((c) => c.id === product.categoryId)?.name ?? "-"}
                            </td>
                            <td className="px-4 py-3 text-text-primary">{formatBRL(product.effectivePriceCents)}</td>
                            <td className="px-4 py-3">
                              {product.isAvailable ? (
                                <span className="inline-flex items-center gap-1 text-success">
                                  <CheckCircle className="h-3.5 w-3.5" aria-hidden="true" />Disponível
                                </span>
                              ) : (
                                <span className="inline-flex items-center gap-1 text-error">
                                  <XCircle className="h-3.5 w-3.5" aria-hidden="true" />Indisponível
                                </span>
                              )}
                            </td>
                            <td className="px-4 py-3 text-right">
                              <div className="inline-flex gap-1">
                                <button className="icon-button" aria-label={`Editar ${product.name}`} onClick={() => void editProduct(product)}>
                                  <Pencil className="h-4 w-4" aria-hidden="true" />
                                </button>
                                <button className="icon-button text-error" aria-label={`Remover ${product.name}`} onClick={() => void remove(`/products/${product.id}`, "produto")}>
                                  <Trash2 className="h-4 w-4" aria-hidden="true" />
                                </button>
                              </div>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </section>

                <ProductForm
                  form={productForm}
                  categories={activeCategories}
                  availability={availability}
                  saving={saving}
                  uploading={uploading}
                  onSubmit={saveProduct}
                  onUpload={uploadImage}
                  setForm={setProductForm}
                  setAvailability={setAvailability}
                />
              </div>
            )}

            {tab === "categories" && (
              <CrudPanel title="Categorias" actionLabel="Nova categoria" onNew={() => setCategoryForm(EMPTY_CATEGORY)}>
                <form onSubmit={saveCategory} className="grid gap-3 rounded-lg bg-bg-primary p-4 shadow-card sm:grid-cols-2">
                  <TextInput label="Nome" value={categoryForm.name} onChange={(v) => setCategoryForm((p) => ({ ...p, name: v }))} required />
                  <NumberInput label="Ordem" value={categoryForm.displayOrder} onChange={(v) => setCategoryForm((p) => ({ ...p, displayOrder: v }))} />
                  <label className="grid gap-1 text-sm font-medium text-text-secondary">
                    Cor
                    <div className="flex items-center gap-2">
                      <input type="color" value={categoryForm.colorCode || "#047857"} onChange={(e) => setCategoryForm((p) => ({ ...p, colorCode: e.target.value }))} className="h-10 w-14 cursor-pointer rounded border border-border-medium" />
                      <span className="text-sm text-text-muted">{categoryForm.colorCode}</span>
                    </div>
                  </label>
                  <TextInput label="Ícone/URL" value={categoryForm.iconUrl} onChange={(v) => setCategoryForm((p) => ({ ...p, iconUrl: v }))} />
                  <TextArea label="Descrição" value={categoryForm.description} onChange={(v) => setCategoryForm((p) => ({ ...p, description: v }))} />
                  <SubmitButton saving={saving} editing={!!categoryForm.id} />
                </form>
                <ListItems
                  items={activeCategories}
                  title={(c) => c.name}
                  subtitle={(c) => c.description || `Ordem ${c.displayOrder}`}
                  onEdit={(c) => setCategoryForm({ id: c.id, name: c.name, description: c.description, displayOrder: c.displayOrder, colorCode: c.colorCode ?? "", iconUrl: c.iconUrl ?? "" })}
                  onDelete={(c) => void remove(`/categories/${c.id}`, "categoria")}
                />
              </CrudPanel>
            )}

            {tab === "ingredients" && (
              <CrudPanel title="Insumos" actionLabel="Novo insumo" onNew={() => setIngredientForm(EMPTY_INGREDIENT)}>
                <form onSubmit={saveIngredient} className="grid gap-3 rounded-lg bg-bg-primary p-4 shadow-card sm:grid-cols-2 lg:grid-cols-3">
                  <TextInput label="Nome" value={ingredientForm.name} onChange={(v) => setIngredientForm((p) => ({ ...p, name: v }))} required />
                  <label className="grid gap-1 text-sm font-medium text-text-secondary">
                    Unidade
                    <select className="input" value={ingredientForm.unit} onChange={(e) => setIngredientForm((p) => ({ ...p, unit: e.target.value }))}>
                      <option value="UNIT">UN (unidade)</option>
                      <option value="GRAM">Grama</option>
                      <option value="KILOGRAM">Kg</option>
                      <option value="LITER">Litro</option>
                      <option value="ML">ml</option>
                      <option value="BOX">Caixa</option>
                      <option value="PACK">Pacote</option>
                    </select>
                  </label>
                  <MoneyInput label="Custo unitário" cents={ingredientForm.unitCostCents} onChange={(v) => setIngredientForm((p) => ({ ...p, unitCostCents: v }))} />
                  <DecimalInput label="Estoque" value={ingredientForm.stockQuantity} onChange={(v) => setIngredientForm((p) => ({ ...p, stockQuantity: v }))} />
                  <DecimalInput label="Estoque mínimo" value={ingredientForm.minStock} onChange={(v) => setIngredientForm((p) => ({ ...p, minStock: v }))} />
                  <label className="flex items-center gap-2 text-sm text-text-secondary">
                    <input type="checkbox" checked={ingredientForm.isAllergen} onChange={(e) => setIngredientForm((p) => ({ ...p, isAllergen: e.target.checked }))} />
                    Alérgeno
                  </label>
                  <TextArea label="Descrição" value={ingredientForm.description} onChange={(v) => setIngredientForm((p) => ({ ...p, description: v }))} />
                  <SubmitButton saving={saving} editing={!!ingredientForm.id} />
                </form>
                <ListItems
                  items={activeIngredients}
                  title={(i) => i.name}
                  subtitle={(i) => `${i.stockQuantity} ${i.unit} · ${formatBRL(i.unitCostCents)}`}
                  onEdit={(i) => setIngredientForm({ id: i.id, name: i.name, description: i.description, unit: i.unit, unitCostCents: i.unitCostCents, stockQuantity: i.stockQuantity, minStock: i.minStock, isAllergen: i.isAllergen })}
                  onDelete={(i) => void remove(`/ingredients/${i.id}`, "insumo")}
                />
              </CrudPanel>
            )}
          </>
        )}
      </main>

      {/* Overlay de formulário de produto — tablet (<xl) */}
      {mobileFormOpen && (
        <div className="xl:hidden fixed inset-0 z-50 overflow-y-auto bg-bg-secondary">
          <div className="mx-auto max-w-lg px-4 py-6">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-lg font-bold text-text-primary">
                {productForm.id ? "Editar produto" : "Novo produto"}
              </h2>
              <button
                type="button"
                aria-label="Fechar formulário"
                onClick={() => setMobileFormOpen(false)}
                className="icon-button"
              >
                <X className="h-5 w-5" aria-hidden="true" />
              </button>
            </div>
            <ProductForm
              form={productForm}
              categories={activeCategories}
              availability={availability}
              saving={saving}
              uploading={uploading}
              onSubmit={saveProduct}
              onUpload={uploadImage}
              setForm={setProductForm}
              setAvailability={setAvailability}
            />
          </div>
        </div>
      )}
    </div>
  );
}

function Metric({ icon: Icon, label, value }: { icon: LucideIcon; label: string; value: number }) {
  return (
    <div className="rounded-lg bg-bg-primary p-4 shadow-card">
      <Icon className="mb-3 h-5 w-5 text-primary-700" aria-hidden="true" />
      <p className="text-2xl font-bold text-text-primary">{value}</p>
      <p className="text-xs text-text-secondary">{label}</p>
    </div>
  );
}

function TabButton({ active, onClick, icon: Icon, label }: { active: boolean; onClick: () => void; icon: LucideIcon; label: string }) {
  return (
    <button role="tab" aria-selected={active} onClick={onClick} className={["inline-flex items-center gap-2 px-4 py-3 text-sm font-semibold", active ? "border-b-2 border-primary-700 text-primary-700" : "text-text-secondary"].join(" ")}>
      <Icon className="h-4 w-4" aria-hidden="true" />
      {label}
    </button>
  );
}

function TableHeader({ title, actionLabel, onAction }: { title: string; actionLabel: string; onAction: () => void }) {
  return (
    <div className="flex items-center justify-between border-b border-border-light px-4 py-3">
      <h2 className="text-sm font-semibold text-text-primary">{title}</h2>
      <button className="btn-secondary inline-flex items-center gap-2" onClick={onAction}>
        <Plus className="h-4 w-4" aria-hidden="true" />
        {actionLabel}
      </button>
    </div>
  );
}

function ProductForm(props: {
  form: typeof EMPTY_PRODUCT;
  categories: Category[];
  availability: string[];
  saving: boolean;
  uploading: boolean;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
  onUpload: (event: ChangeEvent<HTMLInputElement>) => void;
  setForm: Dispatch<SetStateAction<typeof EMPTY_PRODUCT>>;
  setAvailability: Dispatch<SetStateAction<string[]>>;
}) {
  const { form, categories, availability, saving, uploading, onSubmit, onUpload, setForm, setAvailability } = props;
  return (
    <form onSubmit={onSubmit} className="rounded-lg bg-bg-primary p-4 shadow-card">
      <h2 className="mb-4 text-sm font-semibold text-text-primary">{form.id ? "Editar produto" : "Novo produto"}</h2>
      <div className="grid gap-3">
        <TextInput label="Nome" value={form.name} onChange={(v) => setForm((p) => ({ ...p, name: v }))} required />
        <TextInput label="SKU" value={form.sku} onChange={(v) => setForm((p) => ({ ...p, sku: v }))} required />
        <label className="grid gap-1 text-sm font-medium text-text-secondary">
          Categoria
          <select className="input" value={form.categoryId} onChange={(e) => setForm((p) => ({ ...p, categoryId: e.target.value }))} required>
            {categories.map((category) => (
              <option key={category.id} value={category.id}>{category.name}</option>
            ))}
          </select>
        </label>
        <TextArea label="Descrição" value={form.description} onChange={(v) => setForm((p) => ({ ...p, description: v }))} />
        <div className="grid grid-cols-2 gap-3">
          <MoneyInput label="Preço" cents={form.priceCents} onChange={(v) => setForm((p) => ({ ...p, priceCents: v }))} />
          <MoneyInput label="Custo" cents={form.costPriceCents} onChange={(v) => setForm((p) => ({ ...p, costPriceCents: v }))} />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <MoneyInput label="Preço promo" cents={form.promoPriceCents} onChange={(v) => setForm((p) => ({ ...p, promoPriceCents: v }))} />
          <NumberInput label="Tempo (min)" value={form.preparationTimeMinutes} min={1} onChange={(v) => setForm((p) => ({ ...p, preparationTimeMinutes: v }))} />
        </div>
        <NumberInput label="Ordem" value={form.displayOrder} onChange={(v) => setForm((p) => ({ ...p, displayOrder: v }))} />
        <TextInput label="URL da imagem" value={form.imageUrl} onChange={(v) => setForm((p) => ({ ...p, imageUrl: v }))} />
        <label className="inline-flex cursor-pointer items-center gap-2 rounded-lg border border-dashed border-border-medium px-3 py-2 text-sm text-text-secondary hover:bg-bg-secondary">
          <ImagePlus className="h-4 w-4" aria-hidden="true" />
          {uploading ? "Enviando..." : "Enviar imagem"}
          <input type="file" accept="image/png,image/jpeg,image/webp,image/gif" className="sr-only" onChange={onUpload} disabled={uploading} />
        </label>
        <div className="grid gap-2">
          <p className="text-sm font-medium text-text-secondary">Canais de venda</p>
          <div className="grid gap-2 sm:grid-cols-2">
            {CHANNELS.map((channel) => (
              <label key={channel.value} className="flex items-center gap-2 text-sm text-text-secondary">
                <input
                  type="checkbox"
                  checked={availability.includes(channel.value)}
                  onChange={(e) => setAvailability((prev) => selectedChannels(prev, channel.value, e.target.checked))}
                />
                {channel.label}
              </label>
            ))}
          </div>
          <p className="text-xs text-text-muted">Nenhum canal marcado significa disponível em todos.</p>
        </div>
        <div className="flex gap-4">
          <label className="flex items-center gap-2 text-sm text-text-secondary">
            <input type="checkbox" checked={form.isAvailable} onChange={(e) => setForm((p) => ({ ...p, isAvailable: e.target.checked }))} />
            Disponível
          </label>
          <label className="flex items-center gap-2 text-sm text-text-secondary">
            <input type="checkbox" checked={form.isFeatured} onChange={(e) => setForm((p) => ({ ...p, isFeatured: e.target.checked }))} />
            Destaque
          </label>
        </div>
        <SubmitButton saving={saving} editing={!!form.id} />
      </div>
    </form>
  );
}

function CrudPanel({ title, actionLabel = "Novo", onNew, children }: { title: string; actionLabel?: string; onNew: () => void; children: ReactNode }) {
  return (
    <section className="grid gap-5">
      <TableHeader title={title} actionLabel={actionLabel} onAction={onNew} />
      {children}
    </section>
  );
}

function ListItems<T extends { id: string }>({ items, title, subtitle, onEdit, onDelete }: { items: T[]; title: (item: T) => string; subtitle: (item: T) => string; onEdit: (item: T) => void; onDelete: (item: T) => void }) {
  return (
    <div className="grid gap-2 md:grid-cols-2 xl:grid-cols-3">
      {items.length === 0 && (
        <p className="text-sm text-text-muted col-span-full py-4 text-center">Nenhum item cadastrado.</p>
      )}
      {items.map((item) => (
        <div key={item.id} className="flex items-center justify-between gap-3 rounded-lg bg-bg-primary p-4 shadow-card">
          <button className="min-w-0 text-left" onClick={() => onEdit(item)}>
            <p className="truncate text-sm font-semibold text-text-primary">{title(item)}</p>
            <p className="truncate text-xs text-text-secondary">{subtitle(item)}</p>
          </button>
          <button className="icon-button" aria-label={`Remover ${title(item)}`} onClick={() => onDelete(item)}>
            <Trash2 className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
      ))}
    </div>
  );
}

function SubmitButton({ saving, editing }: { saving: boolean; editing: boolean }) {
  return (
    <button type="submit" className="btn-primary inline-flex items-center justify-center gap-2" disabled={saving}>
      <Save className="h-4 w-4" aria-hidden="true" />
      {saving ? "Salvando..." : editing ? "Salvar alterações" : "Criar"}
    </button>
  );
}

function TextInput({ label, value, onChange, required = false }: { label: string; value: string; onChange: (value: string) => void; required?: boolean }) {
  return (
    <label className="grid gap-1 text-sm font-medium text-text-secondary">
      {label}
      <input className="input" value={value} onChange={(e) => onChange(e.target.value)} required={required} />
    </label>
  );
}

function TextArea({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <label className="grid gap-1 text-sm font-medium text-text-secondary sm:col-span-2 lg:col-span-3">
      {label}
      <textarea className="input min-h-20 resize-y" value={value} onChange={(e) => onChange(e.target.value)} />
    </label>
  );
}

function NumberInput({ label, value, onChange, min = 0 }: { label: string; value: number; onChange: (value: number) => void; min?: number }) {
  return (
    <label className="grid gap-1 text-sm font-medium text-text-secondary">
      {label}
      <input className="input" type="number" min={min} value={value} onChange={(e) => onChange(Number(e.target.value))} />
    </label>
  );
}

function DecimalInput({ label, value, onChange }: { label: string; value: number; onChange: (value: number) => void }) {
  return (
    <label className="grid gap-1 text-sm font-medium text-text-secondary">
      {label}
      <input className="input" type="number" step="0.001" min="0" value={value} onChange={(e) => onChange(Number(e.target.value))} />
    </label>
  );
}

function MoneyInput({ label, cents, onChange }: { label: string; cents: number; onChange: (value: number) => void }) {
  return (
    <label className="grid gap-1 text-sm font-medium text-text-secondary">
      {label}
      <input className="input" inputMode="decimal" min="0" value={centsInput(cents)} onChange={(e) => onChange(parseCents(e.target.value))} />
    </label>
  );
}
