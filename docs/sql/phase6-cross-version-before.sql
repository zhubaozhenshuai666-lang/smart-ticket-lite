SELECT
    ticket_category_id,
    bucket_version,
    bucket_no,
    available_stock,
    locked_stock,
    sold_stock
FROM ticket_stock_bucket
WHERE ticket_category_id = 2
ORDER BY bucket_version, bucket_no;
