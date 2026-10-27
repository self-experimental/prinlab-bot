ALTER TABLE orders ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN orders.deleted IS 'Reversible removal from customer and operator active lists; audit and payments are retained';
CREATE INDEX IF NOT EXISTS orders_customer_visible_idx ON orders(customer_id, deleted, status);

CREATE TABLE IF NOT EXISTS customer_intake (
    user_id BIGINT PRIMARY KEY REFERENCES customers(telegram_id),
    order_id UUID NOT NULL REFERENCES orders(id),
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('STL', 'PHOTO'))
);
COMMENT ON COLUMN customer_intake.user_id IS 'Authenticated private Telegram user selecting the upload workflow';
COMMENT ON COLUMN customer_intake.order_id IS 'Explicit order receiving the uploaded file';
COMMENT ON COLUMN customer_intake.mode IS 'STL or PHOTO; an unexpected attachment must never switch the selected mode';

CREATE TABLE IF NOT EXISTS promo_codes (
    id UUID PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    application_type VARCHAR(24) NOT NULL CHECK (application_type IN ('USER_LIMIT', 'DATE', 'COMBINED', 'FIRST_ORDER', 'SUBSEQUENT_ORDERS')),
    percent NUMERIC(5, 2) NOT NULL CHECK (percent > 0 AND percent <= 100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    failure_reason VARCHAR(500),
    max_users INTEGER CHECK (max_users > 0),
    max_orders_per_user INTEGER NOT NULL DEFAULT 1 CHECK (max_orders_per_user > 0),
    used_users INTEGER NOT NULL DEFAULT 0 CHECK (used_users >= 0),
    used_orders INTEGER NOT NULL DEFAULT 0 CHECK (used_orders >= 0),
    campaign_group VARCHAR(40),
    prerequisite_code VARCHAR(20),
    prerequisite_paid_users INTEGER NOT NULL DEFAULT 0 CHECK (prerequisite_paid_users >= 0),
    CHECK (expires_at > starts_at)
);
COMMENT ON COLUMN promo_codes.id IS 'Stable promotion identity independent of the displayed code';
COMMENT ON COLUMN promo_codes.code IS 'Case-insensitive normalized public code, up to 20 ASCII letters, digits or underscores';
COMMENT ON COLUMN promo_codes.application_type IS 'Eligibility by user quota, dates, both, first paid order or subsequent orders';
COMMENT ON COLUMN promo_codes.percent IS 'Discount percentage applied only to printing, without a money cap';
COMMENT ON COLUMN promo_codes.created_at IS 'Immutable UTC creation time; code cannot apply before this instant';
COMMENT ON COLUMN promo_codes.starts_at IS 'UTC inclusive start of validity';
COMMENT ON COLUMN promo_codes.expires_at IS 'UTC exclusive expiration, checked for every application';
COMMENT ON COLUMN promo_codes.enabled IS 'Operator-controlled availability';
COMMENT ON COLUMN promo_codes.deleted IS 'Reversible archive flag; redeemed usage is retained';
COMMENT ON COLUMN promo_codes.failure_reason IS 'Optional explanation for non-date eligibility failures';
COMMENT ON COLUMN promo_codes.max_users IS 'Maximum distinct participating customers; null means unlimited';
COMMENT ON COLUMN promo_codes.max_orders_per_user IS 'Maximum orders for one customer with this code, including outstanding reservations';
COMMENT ON COLUMN promo_codes.used_users IS 'Distinct customers with a confirmed paid redemption';
COMMENT ON COLUMN promo_codes.used_orders IS 'Paid redemptions; refunds do not reset the counter';
COMMENT ON COLUMN promo_codes.campaign_group IS 'Optional mutually exclusive campaign, such as WELCOME';
COMMENT ON COLUMN promo_codes.prerequisite_code IS 'Optional earlier promotion whose paid participants unlock this code';
COMMENT ON COLUMN promo_codes.prerequisite_paid_users IS 'Required distinct paid participants of the prerequisite code';

INSERT INTO promo_codes(id, code, application_type, percent, created_at, starts_at, expires_at, max_users, max_orders_per_user, campaign_group)
VALUES ('00000000-0000-4000-9000-000000005001', 'FIRST50', 'USER_LIMIT', 50, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '2026-12-31T21:00:00Z', 2, 1, 'WELCOME');
INSERT INTO promo_codes(id, code, application_type, percent, created_at, starts_at, expires_at, max_users, max_orders_per_user, campaign_group, prerequisite_code, prerequisite_paid_users)
VALUES ('00000000-0000-4000-9000-000000003001', 'FIRST30', 'USER_LIMIT', 30, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '2026-12-31T21:00:00Z', 8, 1, 'WELCOME', 'FIRST50', 2);
INSERT INTO promo_codes(id, code, application_type, percent, created_at, starts_at, expires_at, max_users, max_orders_per_user)
VALUES ('00000000-0000-4000-9000-000000000101', 'TESTFIRST', 'USER_LIMIT', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '2026-12-31T21:00:00Z', 2, 1);
INSERT INTO promo_codes(id, code, application_type, percent, created_at, starts_at, expires_at, max_orders_per_user)
VALUES ('00000000-0000-4000-9000-000000000102', 'TESTDATE', 'DATE', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '2026-12-31T21:00:00Z', 1);
INSERT INTO promo_codes(id, code, application_type, percent, created_at, starts_at, expires_at, max_orders_per_user)
VALUES ('00000000-0000-4000-9000-000000000103', 'TESTFIRSTORDER', 'FIRST_ORDER', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '2026-12-31T21:00:00Z', 1);
INSERT INTO promo_codes(id, code, application_type, percent, created_at, starts_at, expires_at, max_orders_per_user)
VALUES ('00000000-0000-4000-9000-000000000104', 'TESTSEVERALORDERS', 'SUBSEQUENT_ORDERS', 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '2026-12-31T21:00:00Z', 3);

-- Rebuild to remove the old UNIQUE(customer_id, code), which prevented several orders per customer.
CREATE TABLE promotion_reservations_v12 (
    order_id UUID PRIMARY KEY REFERENCES orders(id),
    customer_id UUID NOT NULL REFERENCES customers(id),
    code VARCHAR(20) NOT NULL,
    discount NUMERIC(18, 2) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    redeemed BOOLEAN NOT NULL DEFAULT FALSE,
    promotion_id UUID REFERENCES promo_codes(id)
);
INSERT INTO promotion_reservations_v12(order_id, customer_id, code, discount, expires_at, redeemed, promotion_id)
SELECT r.order_id, r.customer_id, r.code, r.discount, r.expires_at, r.redeemed, p.id
FROM promotion_reservations r LEFT JOIN promo_codes p ON p.code = r.code;
DROP TABLE promotion_reservations;
ALTER TABLE promotion_reservations_v12 RENAME TO promotion_reservations;
COMMENT ON COLUMN promotion_reservations.order_id IS 'Order reserving or redeeming a promotion';
COMMENT ON COLUMN promotion_reservations.customer_id IS 'Customer participating in promotion';
COMMENT ON COLUMN promotion_reservations.code IS 'Code as used when reserved; retained for historical reports';
COMMENT ON COLUMN promotion_reservations.discount IS 'Reserved printing discount; zero while a draft is waiting for calculation';
COMMENT ON COLUMN promotion_reservations.expires_at IS 'UTC deadline for an unpaid reservation';
COMMENT ON COLUMN promotion_reservations.redeemed IS 'True only after confirmed receipt of payment';
COMMENT ON COLUMN promotion_reservations.promotion_id IS 'Stable catalog identity; nullable only for legacy unknown codes';
CREATE INDEX IF NOT EXISTS promotion_customer_usage_idx ON promotion_reservations(promotion_id, customer_id, redeemed);
UPDATE promo_codes SET used_users = (SELECT COUNT(DISTINCT customer_id) FROM promotion_reservations WHERE promotion_id = promo_codes.id AND redeemed = TRUE),
    used_orders = (SELECT COUNT(*) FROM promotion_reservations WHERE promotion_id = promo_codes.id AND redeemed = TRUE);
