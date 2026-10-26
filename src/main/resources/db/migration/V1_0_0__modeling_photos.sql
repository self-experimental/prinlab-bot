CREATE TABLE IF NOT EXISTS modeling_photos (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    telegram_file_id TEXT NOT NULL,
    document BOOLEAN NOT NULL,
    caption VARCHAR(1024) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
COMMENT ON COLUMN modeling_photos.id IS 'Internal reference photo identifier';
COMMENT ON COLUMN modeling_photos.order_id IS 'Order awaiting manual 3D modeling';
COMMENT ON COLUMN modeling_photos.telegram_file_id IS 'Telegram file ID for private operator delivery';
COMMENT ON COLUMN modeling_photos.document IS 'True for an original image document; false for a compressed Telegram photo';
COMMENT ON COLUMN modeling_photos.caption IS 'Customer description accompanying this reference image';
COMMENT ON COLUMN modeling_photos.created_at IS 'UTC time when the reference was received';
CREATE INDEX IF NOT EXISTS modeling_photos_order_idx ON modeling_photos(order_id, created_at);

CREATE TABLE IF NOT EXISTS operator_accounts (
    username VARCHAR(32) PRIMARY KEY,
    telegram_id BIGINT NOT NULL UNIQUE,
    bound_at TIMESTAMP WITH TIME ZONE NOT NULL
);
COMMENT ON COLUMN operator_accounts.username IS 'Configured Telegram username at first enrollment; never rebound automatically';
COMMENT ON COLUMN operator_accounts.telegram_id IS 'Stable Telegram user ID authorized for operator actions';
COMMENT ON COLUMN operator_accounts.bound_at IS 'UTC time of first authenticated Telegram update';

CREATE TABLE IF NOT EXISTS operator_chat (id INTEGER PRIMARY KEY, telegram_chat_id BIGINT);
COMMENT ON COLUMN operator_chat.id IS 'Singleton operator notification destination';
COMMENT ON COLUMN operator_chat.telegram_chat_id IS 'Group or supergroup Telegram ID; null uses private operator chats';
INSERT INTO operator_chat(id, telegram_chat_id) VALUES (1, NULL);
