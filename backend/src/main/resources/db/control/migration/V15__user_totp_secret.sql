-- MenuFlow CONTROL — persistencia do segredo TOTP (2FA) do SUPER_ADMIN — Flyway V15.
--
-- O 2FA (RFC 6238) e obrigatorio para SUPER_ADMINs (acesso cross-tenant ao painel
-- de plataforma). Ate a Fase F3 o segredo ficava so em memoria (perdido a cada
-- restart); esta migration persiste o segredo CIFRADO em repouso.
--
-- Cifra: AES-256-GCM (mesmo padrao dos tokens iFood — ver IfoodTokenCipher.kt).
-- Cada segredo tem seu proprio IV aleatorio de 12 bytes. A chave de cifra NAO vive
-- no banco: vem de env (IFOOD_ENCRYPTION_KEY / ifood.encryption.key). O tag de
-- autenticacao de 128 bits garante integridade (decrypt falha se enc/iv/chave nao batem).
--
-- Ambas colunas nullable: 2FA e opcional por usuario (so ativado apos setup+confirm);
-- usuario sem 2FA tem ambas NULL. Um segredo valido SEMPRE tem enc E iv juntos
-- (gravados na mesma operacao) — nunca um sem o outro.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS totp_secret_enc BYTEA,
    ADD COLUMN IF NOT EXISTS totp_secret_iv  BYTEA;

COMMENT ON COLUMN users.totp_secret_enc IS
    'Segredo TOTP (2FA) cifrado em AES-256-GCM. NULL = 2FA nao configurado. Nunca em claro.';
COMMENT ON COLUMN users.totp_secret_iv IS
    'IV de 12 bytes do AES-256-GCM do segredo TOTP. Emparelhado com totp_secret_enc.';
