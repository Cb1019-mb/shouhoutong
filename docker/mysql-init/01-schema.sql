-- 售后通 8 张核心表(W1 全量建好,后续模块直接用)
SET NAMES utf8mb4;
CREATE TABLE customer (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    name        VARCHAR(64)  NOT NULL,
    phone       VARCHAR(20)  NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE orders (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id  BIGINT       NOT NULL,
    order_no     VARCHAR(32)  NOT NULL UNIQUE,
    product_name VARCHAR(128) NOT NULL,
    category     VARCHAR(20)  NOT NULL DEFAULT 'GENERAL' COMMENT 'GENERAL/FRESH/CUSTOM(生鲜/定制不可退)',
    amount       DECIMAL(10,2) NOT NULL,
    status       VARCHAR(20)  NOT NULL COMMENT 'PAID/SHIPPED/SIGNED/CLOSED/REFUNDING/REFUNDED',
    signed_at    DATETIME     NULL COMMENT '四态机判定"已签收是否<=7天"依据',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_orders_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refund_request (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    order_id    BIGINT        NOT NULL,
    reason      VARCHAR(255)  NOT NULL,
    amount      DECIMAL(10,2) NOT NULL,
    type        VARCHAR(20)   NOT NULL COMMENT 'ONLY_REFUND/RETURN_REFUND',
    status      VARCHAR(20)   NOT NULL COMMENT 'PENDING_APPROVAL/EXECUTED/REJECTED',
    approval_id BIGINT        NULL,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_refund_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE conversation (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    customer_id BIGINT      NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE message (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT      NOT NULL,
    role            VARCHAR(16) NOT NULL COMMENT 'user/assistant/tool',
    content         TEXT        NOT NULL,
    tool_calls_json JSON        NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_message_conv (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tool_audit (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT      NULL COMMENT '全局型工具(如 searchPolicy)可为空',
    tool_name       VARCHAR(64) NOT NULL,
    params_masked   TEXT        NOT NULL,
    result_summary  VARCHAR(255) NOT NULL,
    risk_level      VARCHAR(8)  NOT NULL COMMENT 'LOW/MID/HIGH',
    policy_decision VARCHAR(8)  NOT NULL COMMENT 'ALLOW/APPROVE/DENY/EXPIRE',
    approval_id     BIGINT      NULL,
    latency_ms      INT         NOT NULL DEFAULT 0,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_audit_conv (conversation_id),
    INDEX idx_audit_day ((DATE(created_at)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE approval_task (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    tool_audit_id   BIGINT      NOT NULL,
    conversation_id BIGINT      NOT NULL,
    order_no        VARCHAR(32) NULL COMMENT '冗余订单号:提交防重与列表展示',
    preview_json    JSON        NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/EXPIRED',
    feishu_msg_id   VARCHAR(64) NULL,
    decider         VARCHAR(64) NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at      DATETIME    NULL,
    INDEX idx_approval_scan (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE policy_hit_stat (
    id        BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_key  VARCHAR(64) NOT NULL,
    hit_count INT         NOT NULL DEFAULT 0,
    date      DATE        NOT NULL,
    UNIQUE KEY uk_rule_date (rule_key, date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 运行时设置(设置页持久化;yml 值作为默认兜底)
CREATE TABLE app_setting (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    setting_key   VARCHAR(64)  NOT NULL UNIQUE,
    setting_value VARCHAR(512) NOT NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 种子数据:3 个模拟客户(演示剧本角色)
INSERT INTO customer (id, name, phone) VALUES
    (1, '张小明', '13800000001'),
    (2, '李婷',   '13800000002'),
    (3, '王芳',   '13800000003');
