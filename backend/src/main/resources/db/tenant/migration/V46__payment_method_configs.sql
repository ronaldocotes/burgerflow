-- Fase CONFIG-A / issue #8: formas de pagamento configuraveis com taxa e repasse.
-- Toggle por forma (PIX/cartao/dinheiro/vale), cada uma com taxa (%) e opcao de
-- "repassar a taxa ao cliente". Catalogo de config no banco do tenant (1 linha por
-- forma). NOTE: never edit once applied.
--
-- UNIFICACAO com V22: tenant_config.card_fee_pct/marketplace_fee_pct (usados hoje
-- pelo DRE como aliquota unica) NAO sao removidos aqui — o DRE continua lendo os
-- snapshots do pedido/aliquotas existentes. Este catalogo e a nova fonte de verdade
-- para o comportamento de CHECKOUT (mostrar formas ativas + repasse). Para dar
-- continuidade, semeamos a taxa de CREDIT_CARD/DEBIT_CARD a partir do card_fee_pct
-- atual. Unificar o DRE para ler desta tabela e um passo futuro (fora do escopo #8).
CREATE TABLE IF NOT EXISTS payment_method_configs (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Chave da forma (nome do enum PaymentMethod + extras como MEAL_VOUCHER).
    method               varchar(30) NOT NULL,
    -- Rotulo exibido no checkout (ex.: "Cartao de credito", "Vale-refeicao").
    label                varchar(60) NOT NULL,
    -- Forma habilitada para uso no checkout.
    enabled              boolean NOT NULL DEFAULT true,
    -- Taxa (%) da forma. 0..100. Usada no repasse e (futuramente) no DRE.
    fee_pct              numeric(5,2) NOT NULL DEFAULT 0,
    -- Se true, a taxa e somada ao total cobrado do cliente (repasse).
    pass_fee_to_customer boolean NOT NULL DEFAULT false,
    sort_order           int NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

-- Uma linha por forma (chave natural). Impede duplicata na config.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_method_configs_method
    ON payment_method_configs (method);

-- Semeia as formas padrao (idempotente). Cartoes herdam o card_fee_pct atual do
-- tenant_config para continuidade com o que o DRE ja assumia.
INSERT INTO payment_method_configs (method, label, enabled, fee_pct, pass_fee_to_customer, sort_order)
SELECT 'PIX', 'PIX', true, 0, false, 10
WHERE NOT EXISTS (SELECT 1 FROM payment_method_configs WHERE method = 'PIX');

INSERT INTO payment_method_configs (method, label, enabled, fee_pct, pass_fee_to_customer, sort_order)
SELECT 'CASH', 'Dinheiro', true, 0, false, 20
WHERE NOT EXISTS (SELECT 1 FROM payment_method_configs WHERE method = 'CASH');

INSERT INTO payment_method_configs (method, label, enabled, fee_pct, pass_fee_to_customer, sort_order)
SELECT 'CREDIT_CARD', 'Cartao de credito', true,
       COALESCE((SELECT card_fee_pct FROM tenant_config ORDER BY created_at LIMIT 1), 0), false, 30
WHERE NOT EXISTS (SELECT 1 FROM payment_method_configs WHERE method = 'CREDIT_CARD');

INSERT INTO payment_method_configs (method, label, enabled, fee_pct, pass_fee_to_customer, sort_order)
SELECT 'DEBIT_CARD', 'Cartao de debito', true,
       COALESCE((SELECT card_fee_pct FROM tenant_config ORDER BY created_at LIMIT 1), 0), false, 40
WHERE NOT EXISTS (SELECT 1 FROM payment_method_configs WHERE method = 'DEBIT_CARD');

INSERT INTO payment_method_configs (method, label, enabled, fee_pct, pass_fee_to_customer, sort_order)
SELECT 'MEAL_VOUCHER', 'Vale-refeicao', false, 0, false, 50
WHERE NOT EXISTS (SELECT 1 FROM payment_method_configs WHERE method = 'MEAL_VOUCHER');
