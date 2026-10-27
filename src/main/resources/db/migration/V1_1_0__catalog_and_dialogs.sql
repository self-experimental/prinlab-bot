CREATE TABLE IF NOT EXISTS filament_colors (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    surface VARCHAR(40),
    manufacturer VARCHAR(120),
    palette_number VARCHAR(80),
    bambu_article VARCHAR(160),
    available BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE
);
COMMENT ON COLUMN filament_colors.id IS 'Stable identifier retained in historical orders';
COMMENT ON COLUMN filament_colors.name IS 'Customer-visible PLA color name';
COMMENT ON COLUMN filament_colors.surface IS 'Surface finish; null when not supplied';
COMMENT ON COLUMN filament_colors.manufacturer IS 'Filament manufacturer; null when unknown';
COMMENT ON COLUMN filament_colors.palette_number IS 'Manufacturer palette number when supplied';
COMMENT ON COLUMN filament_colors.bambu_article IS 'Bambu Lab product article when supplied';
COMMENT ON COLUMN filament_colors.available IS 'Color can be selected for a new order';
COMMENT ON COLUMN filament_colors.deleted IS 'Hidden from catalog while retaining order references';
INSERT INTO filament_colors(id, name) VALUES ('00000000-0000-4000-8000-000000000001', 'Чёрный');
INSERT INTO filament_colors(id, name) VALUES ('00000000-0000-4000-8000-000000000002', 'Белый');
INSERT INTO filament_colors(id, name, surface, manufacturer, bambu_article)
VALUES ('00000000-0000-4000-8000-000000000003', 'Бежевый', 'Матовая', 'Bambu PLA Lite', 'A18-P0-1.75-1000-SPLFREE');

ALTER TABLE orders ADD COLUMN IF NOT EXISTS color_id UUID REFERENCES filament_colors(id);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS layer_height_mm NUMERIC(4, 2);
UPDATE orders SET layer_height_mm = CASE WHEN print_profile = 'FINE' THEN 0.12 ELSE 0.20 END WHERE layer_height_mm IS NULL;
ALTER TABLE orders ALTER COLUMN layer_height_mm SET DEFAULT 0.20;
ALTER TABLE orders ALTER COLUMN layer_height_mm SET NOT NULL;
ALTER TABLE orders ADD CONSTRAINT orders_layer_height_check CHECK (layer_height_mm BETWEEN 0.08 AND 0.28);
COMMENT ON COLUMN orders.color_id IS 'Selected catalog color; null on legacy orders until a color is chosen';
COMMENT ON COLUMN orders.print_profile IS 'STANDARD, FINE or CUSTOM; actual value is in layer_height_mm';
COMMENT ON COLUMN orders.layer_height_mm IS 'Requested layer height in millimeters for the 0.4 mm nozzle';

-- Preserve values while replacing the old unnamed CHECK on 15/30/60 in both PostgreSQL and H2.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS infill_percent INTEGER;
UPDATE orders SET infill_percent = infill;
ALTER TABLE orders ALTER COLUMN infill_percent SET NOT NULL;
ALTER TABLE orders DROP COLUMN infill;
ALTER TABLE orders RENAME COLUMN infill_percent TO infill;
ALTER TABLE orders ALTER COLUMN infill SET DEFAULT 15;
ALTER TABLE orders ADD CONSTRAINT orders_infill_range_check CHECK (infill BETWEEN 1 AND 100);
COMMENT ON COLUMN orders.infill IS 'Requested infill percentage, 1 to 100; below 5 requires a UI warning';

CREATE TABLE IF NOT EXISTS bot_dialogs (
    user_id BIGINT NOT NULL,
    chat_id BIGINT NOT NULL,
    kind VARCHAR(32) NOT NULL,
    step VARCHAR(40),
    order_id UUID REFERENCES orders(id),
    payload TEXT,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, chat_id)
);
COMMENT ON COLUMN bot_dialogs.user_id IS 'Authenticated Telegram user owning the input session';
COMMENT ON COLUMN bot_dialogs.chat_id IS 'Private or operator group chat; isolates simultaneous operator input';
COMMENT ON COLUMN bot_dialogs.kind IS 'Expected customer or operator input workflow';
COMMENT ON COLUMN bot_dialogs.step IS 'Current field in the workflow';
COMMENT ON COLUMN bot_dialogs.order_id IS 'Order context where applicable';
COMMENT ON COLUMN bot_dialogs.payload IS 'Draft values for a multi-step interaction';
COMMENT ON COLUMN bot_dialogs.updated_at IS 'UTC last input change';

ALTER TABLE outbox ADD COLUMN IF NOT EXISTS order_id UUID REFERENCES orders(id);
COMMENT ON COLUMN outbox.order_id IS 'Optional order context used to route replies to delivered bot messages';
CREATE TABLE IF NOT EXISTS order_messages (
    chat_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    order_id UUID NOT NULL REFERENCES orders(id),
    PRIMARY KEY (chat_id, message_id)
);
COMMENT ON COLUMN order_messages.chat_id IS 'Telegram chat receiving the order card or conversation message';
COMMENT ON COLUMN order_messages.message_id IS 'Telegram message ID returned after successful delivery';
COMMENT ON COLUMN order_messages.order_id IS 'Verified order context for a reply in this chat';

CREATE TABLE IF NOT EXISTS modeling_receipts (
    order_id UUID PRIMARY KEY REFERENCES orders(id),
    due_at TIMESTAMP WITH TIME ZONE NOT NULL
);
COMMENT ON COLUMN modeling_receipts.order_id IS 'Modeling order waiting for one acknowledgement after incoming photos';
COMMENT ON COLUMN modeling_receipts.due_at IS 'UTC quiet-period deadline, postponed when another photo arrives';

CREATE TABLE IF NOT EXISTS delivery_details (
    order_id UUID PRIMARY KEY REFERENCES orders(id),
    method VARCHAR(16) NOT NULL CHECK (method IN ('COURIER', 'PICKUP')),
    full_name VARCHAR(200) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    address VARCHAR(1000) NOT NULL,
    priced_quote_id UUID REFERENCES quotes(id)
);
COMMENT ON COLUMN delivery_details.order_id IS 'Order delivered by CDEK';
COMMENT ON COLUMN delivery_details.method IS 'COURIER to a street address or PICKUP to a CDEK collection point';
COMMENT ON COLUMN delivery_details.full_name IS 'Full name of the recipient supplied by the customer';
COMMENT ON COLUMN delivery_details.phone IS 'Recipient phone number including country code';
COMMENT ON COLUMN delivery_details.address IS 'Customer supplied city and street or collection point address';
COMMENT ON COLUMN delivery_details.priced_quote_id IS 'Final quote with delivery cost entered by an operator; null until priced';
