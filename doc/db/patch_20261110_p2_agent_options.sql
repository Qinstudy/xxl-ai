SET NAMES utf8mb4;

-- ====================================================================
-- XXL-AI [FEAT-006] 大模型推理超参数动态配置 (Options) 补丁
-- 表：xxl_ai_agent 追加 temperature, top_p, max_tokens 字段
-- ====================================================================

ALTER TABLE `xxl_ai_agent`
    ADD COLUMN `temperature` DOUBLE NULL DEFAULT NULL COMMENT '模型采样温度(0.0~2.0)，为空采用模型默认值' AFTER `memory_status`,
    ADD COLUMN `top_p` DOUBLE NULL DEFAULT NULL COMMENT '模型核采样(0.0~1.0)，为空采用模型默认值' AFTER `temperature`,
    ADD COLUMN `max_tokens` INT NULL DEFAULT NULL COMMENT '单次生成最大Token数，为空采用模型默认值' AFTER `top_p`;
