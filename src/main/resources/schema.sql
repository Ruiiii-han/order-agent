-- =====================================================================
-- order-agent 表结构（H2 / MySQL 兼容）
-- 演示环境用 H2 内存库；生产切 MySQL 只需改 datasource.url 与驱动
-- =====================================================================

-- ---------------- 业务域：订单履约链路 ----------------
CREATE TABLE IF NOT EXISTS order_main (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32)  NOT NULL UNIQUE,
    user_id       BIGINT       NOT NULL,
    sku_id        BIGINT       NOT NULL,
    amount        DECIMAL(12,2) NOT NULL,
    status        VARCHAR(32)  NOT NULL,   -- CREATED/PAID/STOCK_LOCKED/SHIPPED/CLOSED
    pay_status    VARCHAR(16)  NOT NULL,   -- UNPAID/PAYING/SUCCESS/FAILING/REFUNDED
    stock_status  VARCHAR(16)  NOT NULL,   -- NONE/LOCKED/OUT_OF_STOCK
    create_time   DATETIME     NOT NULL,
    update_time   DATETIME     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_order_user_time ON order_main (user_id, create_time);

CREATE TABLE IF NOT EXISTS pay_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no    VARCHAR(32) NOT NULL,
    pay_channel VARCHAR(16),
    trade_no    VARCHAR(64),
    status      VARCHAR(16),        -- SUCCESS/FAILING/REFUNDED/PENDING
    fail_count  INT DEFAULT 0,
    paid_at     DATETIME
);
CREATE INDEX IF NOT EXISTS idx_pay_order ON pay_record (order_no);

CREATE TABLE IF NOT EXISTS stock_record (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_id    BIGINT NOT NULL,
    warehouse VARCHAR(32),
    available INT NOT NULL,
    locked    INT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_stock_sku ON stock_record (sku_id);

CREATE TABLE IF NOT EXISTS logistics_record (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32) NOT NULL,
    carrier       VARCHAR(32),
    tracking_no   VARCHAR(64),
    latest_status VARCHAR(32),   -- NOT_PICKED/IN_TRANSIT/DELIVERED/EXCEPTION
    latest_time   DATETIME,
    exception_desc VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_logistics_order ON logistics_record (order_no);

CREATE TABLE IF NOT EXISTS risk_record (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no   VARCHAR(32),
    risk_level VARCHAR(16),    -- LOW/MID/HIGH
    rule_name  VARCHAR(64),
    created_at DATETIME
);
CREATE INDEX IF NOT EXISTS idx_risk_order ON risk_record (order_no);

-- 故障注入：演示与评测构造场景用
CREATE TABLE IF NOT EXISTS fault_injection (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    target      VARCHAR(64) NOT NULL,   -- order_no 或 tool 名
    fault_type  VARCHAR(32) NOT NULL,   -- SLOW/TIMEOUT/ERROR/EMPTY
    enabled     TINYINT DEFAULT 1,
    created_at  DATETIME
);

-- ---------------- 智能体域：会话与工具日志 ----------------
CREATE TABLE IF NOT EXISTS diag_session (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      VARCHAR(64) NOT NULL UNIQUE,
    order_no        VARCHAR(32) NOT NULL,
    user_id         BIGINT      NOT NULL,
    question        CLOB,
    answer          CLOB,
    conclusion_code VARCHAR(32),
    confidence      DECIMAL(4,2),
    prompt_version  VARCHAR(16),
    model           VARCHAR(32),
    total_tokens    INT,
    cost            DECIMAL(10,6),
    latency_ms      INT,
    status          VARCHAR(16),   -- RUNNING/DONE/FAILED
    create_time     DATETIME
);

CREATE TABLE IF NOT EXISTS tool_call_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64),
    step_no     INT,
    tool_name   VARCHAR(64),
    arguments   CLOB,
    result      CLOB,
    summary     CLOB,
    success     TINYINT,
    error_msg   VARCHAR(512),
    duration_ms INT,
    create_time DATETIME
);
CREATE INDEX IF NOT EXISTS idx_tool_log_session ON tool_call_log (session_id);

-- ---------------- 评测域 ----------------
CREATE TABLE IF NOT EXISTS eval_case (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_version     VARCHAR(16) NOT NULL,
    case_no             VARCHAR(32) NOT NULL,
    order_no            VARCHAR(32) NOT NULL,
    question            VARCHAR(512),
    expected_conclusion VARCHAR(32) NOT NULL,
    expected_evidence   CLOB,          -- JSON 数组，如 ["pay_timeout","3次失败"]
    must_mention        CLOB,          -- JSON 数组，建议文本中应出现的关键词
    category            VARCHAR(32),   -- 场景分类，用于分析失败分布
    notes               VARCHAR(255)
);
CREATE INDEX IF NOT EXISTS idx_case_version ON eval_case (dataset_version);

CREATE TABLE IF NOT EXISTS eval_run (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_no          VARCHAR(32) NOT NULL UNIQUE,
    dataset_version VARCHAR(16),
    prompt_version  VARCHAR(16),
    model           VARCHAR(32),
    total           INT,
    passed          INT,
    accuracy        DECIMAL(6,4),
    evidence_rate   DECIMAL(6,4),
    judge_avg       DECIMAL(6,2),
    avg_latency_ms  INT,
    p99_latency_ms  INT,
    total_tokens    INT,
    total_cost      DECIMAL(10,6),
    triggered_by    VARCHAR(16),   -- MANUAL/SCHEDULE
    started_at      DATETIME,
    finished_at     DATETIME
);

CREATE TABLE IF NOT EXISTS eval_run_item (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_no             VARCHAR(32),
    case_id            BIGINT,
    case_no            VARCHAR(32),
    question           VARCHAR(512),
    expected_conclusion VARCHAR(32),
    actual_conclusion  VARCHAR(32),
    conclusion_match   TINYINT,
    evidence_match     TINYINT,
    judge_score        INT,
    judge_reason       CLOB,
    passed             TINYINT,
    latency_ms         INT,
    tokens             INT
);
CREATE INDEX IF NOT EXISTS idx_item_run ON eval_run_item (run_no);