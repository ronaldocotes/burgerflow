// Tipos da Fase CONFIG-B (personalização/tema). Espelham os DTOs do backend:
//  - Tema (#12): campos theme* dentro de TenantConfigResponse (GET/PATCH /config).
//  - Pop-up de gestão (#13): GET/PUT /config/entry-popup.
//  - Aplicação pública: theme + entryPopup em GET /public/{slug}/menu.
// Dinheiro sempre em CENTAVOS (number). Verificado nos DTOs em main (61a9030).

import type { Product } from "./menu";

/** Contraste WCAG da cor de marca calculado no servidor (ThemeContrastInfo). */
export interface ThemeContrastInfo {
  primaryColor: string;
  ratioOnWhite: number;
  ratioOnBlack: number;
  recommendedTextColor: string;
  meetsAA: boolean;
}

/**
 * Subconjunto de TenantConfigResponse usado pela tela de Personalização.
 * autoAcceptOrders é obrigatório no PATCH /config (não-nulo no DTO) — precisa ser
 * reenviado como passthrough em todo save baseado em /config, senão 400.
 */
export interface ThemeConfig {
  autoAcceptOrders: boolean;
  themePrimaryColor: string | null;
  themeShowPrices: boolean;
  themeShowDescriptions: boolean;
  themeShowPhotos: boolean;
  themeContrast: ThemeContrastInfo | null;
}

/**
 * Rascunho editável da aparência na tela de gestão (estado local antes de salvar).
 * primaryColor: "" = usar o default do sistema; caso contrário hex (pode estar
 * parcial/inválido enquanto o dono digita — o save valida antes de habilitar).
 */
export interface ThemeDraft {
  primaryColor: string;
  showPrices: boolean;
  showDescriptions: boolean;
  showPhotos: boolean;
}

/** Rascunho editável do pop-up (gestão). productIds na ordem de exibição. */
export interface PopupDraft {
  enabled: boolean;
  title: string;
  productIds: string[];
}

/** Corpo do PATCH /config para salvar só a aparência (patch parcial + passthrough). */
export interface ThemePatch {
  autoAcceptOrders: boolean;
  /** "" limpa a cor (volta ao default); hex inválido → 400. Omitir = preservar. */
  themePrimaryColor?: string;
  themeShowPrices?: boolean;
  themeShowDescriptions?: boolean;
  themeShowPhotos?: boolean;
}

/** Produto no pop-up, visão de gestão (GET /config/entry-popup). */
export interface EntryPopupProduct {
  productId: string;
  name: string;
  priceCents: number;
  effectivePriceCents: number;
  imageUrl: string | null;
  /** false = produto desativado depois de entrar no pop-up (dono deve trocar). */
  active: boolean;
  sortOrder: number;
}

/** Estado do pop-up (gestão): GET /config/entry-popup. */
export interface EntryPopupConfig {
  enabled: boolean;
  title: string | null;
  products: EntryPopupProduct[];
}

/** Corpo do PUT /config/entry-popup — replace atômico (até 3 ids na ordem). */
export interface EntryPopupPut {
  enabled: boolean;
  title: string | null;
  productIds: string[];
}

/** Tema aplicado no cardápio público (GET /public/{slug}/menu → theme). */
export interface PublicTheme {
  primaryColor: string | null;
  recommendedTextColor: string | null;
  showPrices: boolean;
  showDescriptions: boolean;
  showPhotos: boolean;
}

/** Pop-up exposto no cardápio público (só quando enabled; produtos já filtrados). */
export interface PublicEntryPopup {
  enabled: boolean;
  title: string | null;
  products: Product[];
}

/**
 * Mensagem postMessage do preview vivo (§4 do design). O MenuPreviewFrame envia o
 * estado EDITADO (antes de salvar) para o iframe do /cardapio?preview=1 aplicar por
 * cima do que veio da API. Validar event.origin (mesma origem) no receptor.
 */
export interface PreviewMessage {
  type: "mf-preview";
  primaryColor: string | null;
  textColor: string;
  showPrices: boolean;
  showDescriptions: boolean;
  showPhotos: boolean;
  popup: { title: string | null; productIds: string[] } | null;
  showPopup: boolean;
}
