-- MenuFlow TENANT — roteirizacao de multiplas entregas (issue #4) — Flyway V63.
-- Vive no banco do TENANT (db-per-tenant): 1 restaurante por banco, sem coluna de
-- escopo (o banco fisico ja isola). Complementa o despacho de FROTA: quando um
-- motoboy sai com N pedidos, gravamos a POSICAO de cada pedido na rota otimizada
-- para o app do motoboy exibir a ordem de parada.
-- NOTE: never edit this file once applied — Flyway tracks by checksum.
--
-- Escopo desta migracao:
--  1) orders.delivery_sequence: posicao (1-based) do pedido na rota atribuida a um
--     motoboy. Aditivo e NULLABLE — pedido sem rota (balcao/mesa, ou entrega ainda
--     nao roteirizada) fica NULL; nenhum pedido existente quebra. So faz sentido em
--     conjunto com driver_id (a rota e de UM motoboy).
ALTER TABLE orders ADD COLUMN delivery_sequence INT NULL;

-- Posicao 1-based: nunca zero/negativa (o F1 numera a partir de 1).
ALTER TABLE orders
    ADD CONSTRAINT chk_orders_delivery_sequence CHECK (delivery_sequence IS NULL OR delivery_sequence >= 1);

-- O app do motoboy lista as entregas do PROPRIO motoboy ordenadas pela rota
-- (findActiveOrdersForDriver). Indice parcial so nas linhas roteirizadas.
CREATE INDEX idx_orders_driver_sequence
    ON orders (driver_id, delivery_sequence)
    WHERE driver_id IS NOT NULL AND delivery_sequence IS NOT NULL;
