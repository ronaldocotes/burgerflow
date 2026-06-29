-- MenuFlow CONTROL — golden set de avaliacao do Copiloto (Fase 4.2) — Flyway V6.
-- Conjunto canonico de perguntas com as ferramentas esperadas, usado para avaliar
-- (eval) a qualidade do roteamento de tools do copiloto contra um tenant real.
-- Vive no banco de CONTROLE (global): e a mesma referencia para TODOS os tenants.
--
-- O controle roda hibernate.ddl-auto=validate, entao este DDL e a fonte de verdade —
-- manter em sincronia com a entidade com.menuflow.model.control.AiGoldenQuestion.
-- NUNCA editar apos aplicada (Flyway rastreia por checksum); mudancas seguem em V7, ...
CREATE TABLE ai_golden_questions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    question       TEXT        NOT NULL,
    expected_tools TEXT        NOT NULL,  -- JSON array de nomes de ferramentas esperadas
    category       VARCHAR(50) NOT NULL,  -- DRE, PRODUTOS, CLIENTES, FIDELIDADE, CAMPANHAS...
    active         BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed das perguntas canonicas (uma referencia por categoria de capacidade).
INSERT INTO ai_golden_questions (question, expected_tools, category) VALUES
    ('Como está meu DRE este mês?', '["get_dre"]', 'DRE'),
    ('Qual meu lucro líquido no último mês?', '["get_dre"]', 'DRE'),
    ('Quais produtos vendem mais?', '["get_top_products"]', 'PRODUTOS'),
    ('Me mostre os 5 produtos mais pedidos', '["get_top_products"]', 'PRODUTOS'),
    ('Tenho clientes em risco de perda?', '["get_rfv_summary"]', 'CLIENTES'),
    ('Quantos clientes inativos tenho?', '["get_rfv_summary"]', 'CLIENTES'),
    ('Quantos carrinhos abandonados tenho hoje?', '["get_abandoned_carts"]', 'CARRINHO'),
    ('Como está meu programa de fidelidade?', '["get_loyalty_stats"]', 'FIDELIDADE'),
    ('Quais os últimos 10 pedidos?', '["get_recent_orders"]', 'PEDIDOS'),
    ('Crie um cupom de 15% chamado VOLTA15', '["create_coupon"]', 'ACOES');
