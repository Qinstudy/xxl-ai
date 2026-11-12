-- ========================================================
-- XXL-AI 数据库补丁：[FEAT-009] 轻量 Workflow DAG 执行引擎（阶段一）
-- 包含：工作流定义表 (xxl_ai_workflow) 与工作流执行实例表 (xxl_ai_workflow_execution)
-- 日期：2026-11-12
-- ========================================================

SET NAMES utf8mb4;
USE `xxl_ai`;

-- 15、工作流定义表（基于 JSON 拓扑的高内聚微创设计）
CREATE TABLE IF NOT EXISTS `xxl_ai_workflow` (
    `id`            BIGINT          NOT NULL            AUTO_INCREMENT  COMMENT '主键自增',
    `space_id`      BIGINT          NOT NULL                            COMMENT '所属业务空间ID（多租户隔离）',
    `name`          VARCHAR(100)    NOT NULL                            COMMENT '工作流名称',
    `description`   VARCHAR(255)    DEFAULT ''                          COMMENT '工作流功能描述',
    `status`        TINYINT         NOT NULL DEFAULT 0                  COMMENT '状态：0-草稿/正常, 1-已发布, 2-已停用',
    `graph_json`    MEDIUMTEXT      NOT NULL                            COMMENT 'DAG 图定义 JSON（包含 nodes 与 edges 完整拓扑）',
    `ext_json`      MEDIUMTEXT      DEFAULT NULL                        COMMENT '扩展配置 JSON（全局超时、最大步数、变量定义）',
    `add_time`      DATETIME        NOT NULL                            COMMENT '创建时间',
    `update_time`   DATETIME        NOT NULL                            COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_wf_space_status` (`space_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流定义表';

-- 16、工作流执行记录表（单次执行 Run 实例与逐节点追踪快照）
CREATE TABLE IF NOT EXISTS `xxl_ai_workflow_execution` (
    `id`            BIGINT          NOT NULL            AUTO_INCREMENT  COMMENT '主键自增',
    `space_id`      BIGINT          NOT NULL                            COMMENT '所属业务空间ID（多租户隔离）',
    `workflow_id`   BIGINT          NOT NULL                            COMMENT '关联工作流ID',
    `trigger_type`  VARCHAR(20)     NOT NULL DEFAULT 'DEBUG'            COMMENT '触发类型：DEBUG(调试运行) / API(外部接口) / CHAT(智能体对话触发)',
    `status`        TINYINT         NOT NULL DEFAULT 0                  COMMENT '执行状态：0-运行中, 1-成功, 2-失败, 3-超时中断, 4-已取消',
    `total_tokens`  INT             NOT NULL DEFAULT 0                  COMMENT '累计消耗 Token 数',
    `duration_ms`   INT             NOT NULL DEFAULT 0                  COMMENT '总执行耗时（毫秒）',
    `input_json`    TEXT            DEFAULT NULL                        COMMENT '输入入参 JSON',
    `output_json`   MEDIUMTEXT      DEFAULT NULL                        COMMENT '最终输出结果 JSON',
    `execution_json` MEDIUMTEXT     DEFAULT NULL                        COMMENT '逐节点执行链路与快照 JSON（节点ID/状态/入出参/耗时/Token）',
    `error_msg`     VARCHAR(500)    DEFAULT ''                          COMMENT '失败异常信息',
    `add_time`      DATETIME        NOT NULL                            COMMENT '启动时间',
    `update_time`   DATETIME        NOT NULL                            COMMENT '结束更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_wfe_space_wf` (`space_id`, `workflow_id`),
    KEY `idx_wfe_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流执行记录表';
