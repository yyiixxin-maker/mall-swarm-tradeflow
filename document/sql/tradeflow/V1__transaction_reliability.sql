-- Apply with environment/reliability.py migrate after its duplicate-order preflight.
-- New timestamps are epoch milliseconds, independent of JDBC/session time zones.
CREATE TABLE IF NOT EXISTS order_request_dedup (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    member_id BIGINT NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    order_id BIGINT NULL,
    UNIQUE KEY uk_order_request (member_id, request_key),
    UNIQUE KEY uk_order_request_order (order_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS payment_transaction (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    channel VARCHAR(16) CHARACTER SET ascii NOT NULL,
    merchant_order_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_trade_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    order_id BIGINT NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    app_id VARCHAR(64) CHARACTER SET ascii NOT NULL,
    seller_id VARCHAR(64) CHARACTER SET ascii NOT NULL,
    trade_status VARCHAR(32) CHARACTER SET ascii NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii NOT NULL,
    review_reason VARCHAR(128) NULL,
    verified_at BIGINT NOT NULL,
    UNIQUE KEY uk_payment_merchant (channel, merchant_order_no),
    UNIQUE KEY uk_payment_provider (channel, provider_trade_no),
    KEY ix_payment_review (state, verified_at)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS outbox_event (
    event_id CHAR(36) CHARACTER SET ascii NOT NULL PRIMARY KEY,
    event_type VARCHAR(32) CHARACTER SET ascii NOT NULL,
    order_id BIGINT NOT NULL,
    deadline BIGINT NOT NULL,
    next_attempt BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    published_at BIGINT NULL,
    lease_token CHAR(36) CHARACTER SET ascii NULL,
    lease_until BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(128) NULL,
    UNIQUE KEY uk_outbox_order_type (order_id, event_type),
    KEY ix_outbox_pending (published_at, next_attempt, lease_until),
    KEY ix_outbox_deadline (deadline, order_id)
) ENGINE=InnoDB;

CREATE UNIQUE INDEX uk_oms_order_sn ON oms_order (order_sn);
CREATE INDEX ix_oms_order_timeout ON oms_order (status, delete_status, create_time);
CREATE INDEX ix_oms_order_item_order ON oms_order_item (order_id);
