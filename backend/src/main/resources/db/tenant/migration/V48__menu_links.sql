-- Fase CONFIG-A / issue #11: variantes de link/QR do cardapio.
-- 3 variantes: FULL (cardapio completo com pedido), VIEW_ONLY (apenas visualizacao,
-- sem pedido) e COUNTER (pedido de balcao/mesa). Cada uma tem um slug editavel e
-- pode apontar para uma mesa (COUNTER). Serve para diferenciar QR de mesa x QR de
-- vitrine x link de delivery. NOTE: never edit once applied.
CREATE TABLE IF NOT EXISTS menu_links (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Slug publico editavel (compoe a URL: /public/{tenant}/l/{slug}).
    slug        varchar(60) NOT NULL,
    -- FULL | VIEW_ONLY | COUNTER.
    variant     varchar(20) NOT NULL,
    -- Rotulo interno para o dono identificar o link (ex.: "QR Mesa 5", "Vitrine").
    label       varchar(80) NOT NULL,
    -- Mesa vinculada quando variant=COUNTER (opcional). Sem FK rigida: mesa pode
    -- ser desativada sem quebrar o link.
    table_id    uuid,
    active      boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- Slug unico somente entre links ativos (link desativado libera o slug).
CREATE UNIQUE INDEX IF NOT EXISTS uq_menu_links_slug_active
    ON menu_links (slug) WHERE active = true;
