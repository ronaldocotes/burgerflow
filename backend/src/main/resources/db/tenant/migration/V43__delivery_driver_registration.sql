-- Fase C1: campos legais/cadastrais do motoboy freelancer.
-- O freelancer entrou pelo grupo (provisional=true, V41) e, apos a 1a entrega,
-- recebe o link publico de auto-cadastro (signup_token). Ao concluir o cadastro
-- ele preenche estes dados (documento, veiculo, PIX de repasse, endereco) e deixa
-- de ser provisional. Todos nullable: um motoboy pre-cadastro ainda nao os tem.
-- LGPD: full_address e cpf sao coletados para repasse/identificacao e NUNCA
-- expostos em endpoints publicos (o preview so devolve nome + telefone mascarado).
ALTER TABLE delivery_drivers
  ADD COLUMN IF NOT EXISTS cpf                       VARCHAR(14),
  ADD COLUMN IF NOT EXISTS cnh_category              VARCHAR(5),
  ADD COLUMN IF NOT EXISTS vehicle_type              VARCHAR(20),
  ADD COLUMN IF NOT EXISTS pix_key                   VARCHAR(100),
  ADD COLUMN IF NOT EXISTS pix_key_type              VARCHAR(20),
  ADD COLUMN IF NOT EXISTS full_address              VARCHAR(255),
  ADD COLUMN IF NOT EXISTS registration_completed_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS terms_accepted_at         TIMESTAMPTZ;
