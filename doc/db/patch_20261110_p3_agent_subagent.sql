SET NAMES utf8mb4;

-- ====================================================================
-- XXL-AI [FEAT-013] SubAgents 多智能体协作体系补丁
-- 表：xxl_ai_agent 追加 sub_agent_ids 字段
-- ====================================================================

ALTER TABLE `xxl_ai_agent`
    ADD COLUMN `sub_agent_ids` VARCHAR(500) NULL DEFAULT NULL COMMENT '关联SubAgent ID集合(逗号分隔，最多5个)' AFTER `skill_ids`;
