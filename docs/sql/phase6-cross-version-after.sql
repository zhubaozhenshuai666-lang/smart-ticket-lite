SELECT
    ticket_category_id,
    bucket_version,
    SUM(available_stock) AS available_stock,
    SUM(locked_stock) AS locked_stock,
    SUM(sold_stock) AS sold_stock
FROM ticket_stock_bucket
WHERE ticket_category_id = 2
GROUP BY ticket_category_id, bucket_version
ORDER BY bucket_version;

SELECT
    stock_bucket_version,
    COUNT(1) AS bucket_version_summary
FROM ticket_order_request
WHERE ticket_category_id = 2
GROUP BY stock_bucket_version
ORDER BY stock_bucket_version;
