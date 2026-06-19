-- High-concurrency order pipeline indexes.
--
-- Run these against the target MySQL database after checking existing indexes
-- with SHOW INDEX. MySQL does not support CREATE INDEX IF NOT EXISTS across all
-- supported versions, so do not execute blindly on a database that already has
-- the same index names.

ALTER TABLE ticket_stock
    ADD UNIQUE KEY uk_ticket_stock_category (ticket_category_id);

ALTER TABLE ticket_stock_bucket
    ADD UNIQUE KEY uk_ticket_bucket (ticket_category_id, bucket_version, bucket_no),
    ADD KEY idx_ticket_category_version (ticket_category_id, bucket_version);

ALTER TABLE ticket_order_request
    ADD UNIQUE KEY uk_ticket_order_request_request_id (request_id),
    ADD KEY idx_ticket_order_request_user_request (user_id, request_id),
    ADD KEY idx_ticket_order_request_order_id (order_id),
    ADD KEY idx_ticket_order_request_inflight_calc (
        ticket_category_id,
        status,
        redis_deducted,
        compensated,
        compensation_status,
        deducted_quantity
    ),
    ADD KEY idx_ticket_order_request_compensation_scan (
        status,
        redis_deducted,
        compensated,
        compensation_status,
        updated_at
    ),
    ADD KEY idx_ticket_order_request_bucket_version (
        ticket_category_id,
        stock_bucket_version,
        stock_bucket_no
    );

ALTER TABLE ticket_order
    ADD UNIQUE KEY uk_ticket_order_order_no (order_no),
    ADD KEY idx_ticket_order_user_created (user_id, created_at),
    ADD KEY idx_ticket_order_expire_scan (status, expire_time),
    ADD KEY idx_ticket_order_ticket_status (ticket_category_id, status),
    ADD KEY idx_ticket_order_show_status (show_id, status),
    ADD KEY idx_ticket_order_session_status (session_id, status);

ALTER TABLE local_message
    ADD UNIQUE KEY uk_local_message_message_id (message_id),
    ADD KEY idx_local_message_status_updated_at (status, updated_at),
    ADD KEY idx_local_message_business (business_type, business_key);
