ALTER TABLE filament_colors ADD COLUMN IF NOT EXISTS spool_price NUMERIC(18, 2);
ALTER TABLE filament_colors ADD COLUMN IF NOT EXISTS spool_weight_grams INTEGER NOT NULL DEFAULT 1000;
ALTER TABLE filament_colors ADD CONSTRAINT filament_spool_price_check CHECK (spool_price > 0);
ALTER TABLE filament_colors ADD CONSTRAINT filament_spool_weight_check CHECK (spool_weight_grams BETWEEN 1 AND 100000);
COMMENT ON COLUMN filament_colors.spool_price IS 'Purchase price of one spool in RUB; required before a new order can use the color';
COMMENT ON COLUMN filament_colors.spool_weight_grams IS 'Net filament mass in grams, excluding the spool itself';
UPDATE filament_colors SET spool_price = 700, surface = 'Глянцевая' WHERE id IN ('00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002');
UPDATE filament_colors SET spool_price = 1100, surface = 'Матовая' WHERE id = '00000000-0000-4000-8000-000000000003';

-- Rebuild the method constraint portably on PostgreSQL and H2, retaining historical delivery rows.
CREATE TABLE delivery_details_v13 (
    order_id UUID PRIMARY KEY REFERENCES orders(id),
    method VARCHAR(16) NOT NULL CHECK (method IN ('COURIER', 'PICKUP', 'SELF_PICKUP')),
    full_name VARCHAR(200),
    phone VARCHAR(20),
    address VARCHAR(1000) NOT NULL,
    priced_quote_id UUID REFERENCES quotes(id),
    external_price NUMERIC(18, 2) CHECK (external_price >= 0),
    pickup_key VARCHAR(32)
);
INSERT INTO delivery_details_v13(order_id, method, full_name, phone, address, priced_quote_id)
SELECT order_id, method, full_name, phone, address, priced_quote_id FROM delivery_details;
DROP TABLE delivery_details;
ALTER TABLE delivery_details_v13 RENAME TO delivery_details;
COMMENT ON COLUMN delivery_details.method IS 'CDEK courier or collection point, or free local SELF_PICKUP';
COMMENT ON COLUMN delivery_details.external_price IS 'Informational CDEK fee paid directly to the carrier; excluded from PrintLab costs and receipts';
COMMENT ON COLUMN delivery_details.pickup_key IS 'Stable key of an approved Moscow pickup location';
COMMENT ON COLUMN delivery_details.priced_quote_id IS 'Quote revision associated with the selected fulfillment details';

CREATE TABLE IF NOT EXISTS color_requests (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    description VARCHAR(500) NOT NULL,
    hex_code VARCHAR(7),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    approved_color_id UUID REFERENCES filament_colors(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE
);
COMMENT ON COLUMN color_requests.description IS 'Customer color suggestion, evaluated manually by operators';
COMMENT ON COLUMN color_requests.hex_code IS 'Optional normalized #RRGGBB code supplied by the customer';
COMMENT ON COLUMN color_requests.approved_color_id IS 'Order-specific filament with an operator supplied spool price and net mass';
CREATE INDEX IF NOT EXISTS color_requests_order_idx ON color_requests(order_id, created_at);

-- Only confirmed paid redemptions consume promotion eligibility or quotas.
DELETE FROM promotion_reservations WHERE redeemed = FALSE;
