-- MenuFlow TENANT — reconciliação de fechamento por forma de pagamento — Flyway V51.
-- Estende o turno de caixa (V16) para reconciliar TAMBÉM cartão e PIX no
-- fechamento (Esperado | Em caixa | Diferença por forma), além do dinheiro que
-- já existia. Guarda o snapshot do esperado/contado por forma no momento do
-- fechamento (fonte de verdade histórica: estornos posteriores não reescrevem a
-- conferência do turno). Também registra o dinheiro retirado no fechamento e o
-- saldo sugerido para a próxima abertura. Tudo em db-per-tenant, centavos (BIGINT).
-- NOTE: never edit this file once applied — Flyway tracks by checksum.

ALTER TABLE cash_sessions
    -- Snapshot do CARTÃO no fechamento (crédito + débito somados).
    ADD COLUMN closing_card_counted_cents  BIGINT,
    ADD COLUMN closing_card_expected_cents BIGINT,
    -- Snapshot do PIX no fechamento.
    ADD COLUMN closing_pix_counted_cents    BIGINT,
    ADD COLUMN closing_pix_expected_cents  BIGINT,
    -- Dinheiro retirado da gaveta NO fechamento (sangria final para o cofre/banco).
    -- NÃO entra no cálculo do esperado do turno — o esperado é conferido ANTES da
    -- retirada; este valor só reduz o saldo que sobra para a próxima abertura.
    ADD COLUMN withdrawn_at_close_cents     BIGINT,
    -- Saldo sugerido para a próxima abertura = dinheiro contado - retirado (o que
    -- fica fisicamente na gaveta ao final). Persistido para auditoria e para a
    -- tela de abertura seguinte já vir pré-preenchida.
    ADD COLUMN suggested_next_opening_cents BIGINT;

-- Nenhum dos novos valores pode ser negativo quando presente (defesa em profundidade;
-- o serviço já valida a entrada). Colunas NULL enquanto o turno está aberto.
ALTER TABLE cash_sessions
    ADD CONSTRAINT chk_cash_card_counted_nonneg  CHECK (closing_card_counted_cents  IS NULL OR closing_card_counted_cents  >= 0),
    ADD CONSTRAINT chk_cash_card_expected_nonneg CHECK (closing_card_expected_cents IS NULL OR closing_card_expected_cents >= 0),
    ADD CONSTRAINT chk_cash_pix_counted_nonneg   CHECK (closing_pix_counted_cents   IS NULL OR closing_pix_counted_cents   >= 0),
    ADD CONSTRAINT chk_cash_pix_expected_nonneg  CHECK (closing_pix_expected_cents  IS NULL OR closing_pix_expected_cents  >= 0),
    ADD CONSTRAINT chk_cash_withdrawn_nonneg     CHECK (withdrawn_at_close_cents     IS NULL OR withdrawn_at_close_cents     >= 0),
    ADD CONSTRAINT chk_cash_suggested_nonneg     CHECK (suggested_next_opening_cents IS NULL OR suggested_next_opening_cents >= 0);
