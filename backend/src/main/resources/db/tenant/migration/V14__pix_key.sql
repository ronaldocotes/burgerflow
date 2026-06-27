-- MenuFlow TENANT — chave PIX estatica do restaurante (Flyway V14).
-- Coluna aditiva na linha unica de tenant_config (db-per-tenant). Nullable:
-- restaurante pode nao ter PIX. Sem DEFAULT. VARCHAR(140) cobre chave aleatoria
-- (EVP/UUID), e-mail, telefone e CPF/CNPJ. NOTE: never edit once applied —
-- Flyway tracks by checksum.

ALTER TABLE tenant_config ADD COLUMN pix_key VARCHAR(140);
