CREATE INDEX IF NOT EXISTS idx_order_items_attrs_gin
    ON order_items USING GIN (attributes_json);