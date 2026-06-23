INSERT INTO user_account (
    id, username, phone, password, status, role_code, created_at, updated_at
) VALUES
    (1, 'test_user', '13800000001', '$2a$10$RSRwC1udQEZ3AkWPrA1FH.p/iTmchb28v5ir7YSHRV4t1Tb1Vu31m', 'NORMAL', 'USER', NOW(), NOW()),
    (2, 'test_admin', '13800000002', '$2a$10$RSRwC1udQEZ3AkWPrA1FH.p/iTmchb28v5ir7YSHRV4t1Tb1Vu31m', 'NORMAL', 'ADMIN', NOW(), NOW());

INSERT INTO venue (
    id, name, city, address, capacity, created_at, updated_at
) VALUES (
    1, 'Integration Venue', 'Shanghai', 'No. 1 Integration Road', 50000, NOW(), NOW()
);

INSERT INTO show_info (
    id, title, artist, venue_id, description, status, created_at, updated_at
) VALUES (
    1, 'Integration Show', 'Integration Artist', 1, 'Integration test show', 'PUBLISHED', NOW(), NOW()
);

INSERT INTO performance_session (
    id, show_id, start_time, end_time, status, created_at, updated_at
) VALUES (
    1, 1, '2030-01-01 20:00:00', '2030-01-01 22:00:00', 'PUBLISHED', NOW(), NOW()
);

INSERT INTO ticket_category (
    id, session_id, category_name, price, status, created_at, updated_at
) VALUES
    (1, 1, 'VIP', 1280.00, 'PUBLISHED', NOW(), NOW()),
    (2, 1, 'A', 680.00, 'PUBLISHED', NOW(), NOW());

INSERT INTO ticket_stock (
    id, ticket_category_id, total_stock, available_stock, locked_stock, sold_stock, version, created_at, updated_at
) VALUES
    (1, 1, 100, 100, 0, 0, 0, NOW(), NOW()),
    (2, 2, 1000, 1000, 0, 0, 0, NOW(), NOW());
