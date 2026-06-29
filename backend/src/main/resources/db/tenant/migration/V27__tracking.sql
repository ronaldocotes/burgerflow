-- MenuFlow TENANT — Tracking first-party (Fase 3.6) — Flyway V27.
-- Links rastreaveis (UTM proprio): o dono cria um link com origem/midia/campanha e o
-- sistema gera um slug curto. Cada clique vira um marketing_event CLICK; cada pedido
-- finalizado com o link vira um CONVERSION (com a receita em centavos). O painel ROAS
-- agrega cliques x pedidos x receita por link. Tudo db-per-tenant (1 restaurante/banco).
-- Sem cookies de terceiros — atribuicao first-party, dona do dado.
-- NOTE: never edit this file once applied — Flyway tracks by checksum.

CREATE TABLE tracking_links (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            VARCHAR(12)  NOT NULL UNIQUE,
    name            VARCHAR(100) NOT NULL,
    source          VARCHAR(100) NOT NULL,   -- "whatsapp-junho", "instagram-bio", "panfleto"
    medium          VARCHAR(100),            -- "social", "print", "messaging"
    campaign        VARCHAR(100),            -- nome da campanha (opcional)
    destination_url TEXT         NOT NULL,   -- URL de destino (cardapio publico + UTM)
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    click_count     BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_tracking_links_slug ON tracking_links(slug);

CREATE TABLE marketing_events (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tracking_link_id UUID         NOT NULL REFERENCES tracking_links(id),
    event_type       VARCHAR(20)  NOT NULL,  -- CLICK, CONVERSION
    order_id         UUID         REFERENCES orders(id),
    revenue_cents    BIGINT,                 -- preenchido em CONVERSION
    customer_ip      VARCHAR(45),            -- anonimizado (IPv4 zera ultimo octeto; IPv6 so /64)
    user_agent       VARCHAR(500),
    occurred_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_marketing_events_link ON marketing_events(tracking_link_id);
CREATE INDEX idx_marketing_events_type ON marketing_events(tracking_link_id, event_type);
CREATE INDEX idx_marketing_events_order ON marketing_events(order_id);
