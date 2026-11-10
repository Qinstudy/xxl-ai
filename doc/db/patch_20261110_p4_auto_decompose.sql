SET NAMES utf8mb4;

-- ====================================================================
-- XXL-AI SubAgent 自主任务拆解与多智能体架构演进补丁
-- 表：xxl_ai_agent 追加 auto_decompose_status 字段
-- ====================================================================

ALTER TABLE `xxl_ai_agent`
    ADD COLUMN `auto_decompose_status` TINYINT NOT NULL DEFAULT 0 COMMENT '自主拆解子Agent：0-关闭、1-开启' AFTER `sub_agent_ids`;
