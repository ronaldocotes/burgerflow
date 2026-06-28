-- MenuFlow TENANT — id do customer "avulso" do restaurante no Asaas (Fase 2.3, V19).
-- O PDV/balcao nao cadastra cliente por venda: usamos UM customer fixo por tenant,
-- criado uma vez no Asaas e guardado aqui. Aditivo, nullable (tenant sem PIX ainda
-- nao tem customer). NOTE: never edit this file once applied — Flyway tracks by checksum.

ALTER TABLE tenant_config ADD COLUMN IF NOT EXISTS asaas_customer_id varchar(64);
