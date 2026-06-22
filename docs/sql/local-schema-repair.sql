/*
 * 本地旧库结构修复脚本。
 *
 * 适用场景：
 * - 应用能启动到 Tomcat，但定时任务反复报 Unknown column。
 * - 代码已经升级，MySQL 本地库 smart_ticket_lite 还停留在旧表结构。
 *
 * 执行方式：
 * MYSQL_PWD='你的MySQL密码' mysql --protocol=TCP -h 127.0.0.1 -P 3306 -u root -D smart_ticket_lite < docs/sql/local-schema-repair.sql
 */

DROP PROCEDURE IF EXISTS add_column_if_missing;

DELIMITER //

CREATE PROCEDURE add_column_if_missing(
    IN p_table_name VARCHAR(64),
    IN p_column_name VARCHAR(64),
    IN p_column_definition TEXT,
    IN p_after_column VARCHAR(64)
)
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = p_table_name
          AND COLUMN_NAME = p_column_name
    ) THEN
        SET @ddl = CONCAT(
                'ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ', p_column_definition,
                CASE
                    WHEN p_after_column IS NULL OR p_after_column = '' THEN ''
                    ELSE CONCAT(' AFTER `', p_after_column, '`')
                END
            );
        PREPARE ddl_stmt FROM @ddl;
        EXECUTE ddl_stmt;
        DEALLOCATE PREPARE ddl_stmt;
    END IF;
END//

DELIMITER ;

CALL add_column_if_missing('ticket_category', 'status', 'varchar(32) NOT NULL DEFAULT ''PUBLISHED''', 'price');

CALL add_column_if_missing('user_account', 'role_code', 'varchar(32) NOT NULL DEFAULT ''USER''', 'status');

CALL add_column_if_missing('local_message', 'confirmed_at', 'datetime NULL', 'sent_at');
CALL add_column_if_missing('local_message', 'returned_at', 'datetime NULL', 'confirmed_at');
CALL add_column_if_missing('local_message', 'dead_at', 'datetime NULL', 'returned_at');

CALL add_column_if_missing('stock_adjustment_record', 'confirmed_at', 'datetime NULL', 'confirm_token');
CALL add_column_if_missing('stock_adjustment_record', 'rollback_available', 'tinyint(1) NOT NULL DEFAULT 0', 'confirmed_at');
CALL add_column_if_missing('stock_adjustment_record', 'rollback_record_id', 'bigint NULL', 'rollback_available');

CALL add_column_if_missing('ticket_order', 'show_title', 'varchar(128) NULL', 'quantity');
CALL add_column_if_missing('ticket_order', 'session_start_time', 'datetime NULL', 'show_title');
CALL add_column_if_missing('ticket_order', 'ticket_category_name', 'varchar(64) NULL', 'session_start_time');
CALL add_column_if_missing('ticket_order', 'ticket_price', 'decimal(10,2) NULL', 'ticket_category_name');
CALL add_column_if_missing('ticket_order', 'total_amount', 'decimal(10,2) NULL', 'ticket_price');
CALL add_column_if_missing('ticket_order', 'cancel_reason', 'varchar(255) NULL', 'close_time');
CALL add_column_if_missing('ticket_order', 'version', 'int NOT NULL DEFAULT 0', 'cancel_reason');

UPDATE ticket_category
SET status = 'PUBLISHED'
WHERE status IS NULL OR status = '';

UPDATE user_account
SET role_code = 'USER'
WHERE role_code IS NULL OR role_code = '';

UPDATE ticket_order
SET version = 0
WHERE version IS NULL;

DROP PROCEDURE IF EXISTS add_column_if_missing;
