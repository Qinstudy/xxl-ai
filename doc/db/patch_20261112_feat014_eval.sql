-- ---------------------------------------------------------------------------------------------------------------------
-- XXL-AI: AI 应用与 RAG 质量量化评测体系 [FEAT-014] 数据库补丁脚本
-- 遵循 Monorepo 微创原则：仅微创引入 2 张表，使用 MEDIUMTEXT JSON 存储明细，零表结构冗余
-- 必须指定 utf8mb4 字符集执行
-- ---------------------------------------------------------------------------------------------------------------------

-- 1. 评测数据集表
CREATE TABLE IF NOT EXISTS `xxl_ai_eval_dataset` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键自增',
    `space_id` BIGINT NOT NULL COMMENT '所属业务空间ID（多租户隔离）',
    `name` VARCHAR(100) NOT NULL COMMENT '评测集名称',
    `eval_type` VARCHAR(20) NOT NULL DEFAULT 'RAG' COMMENT '评测类型：RAG（知识库检索与生成）/ AGENT（智能体目标与工具）',
    `target_id` BIGINT NOT NULL DEFAULT 0 COMMENT '评估目标ID：eval_type=RAG 时为 kb_id，eval_type=AGENT 时为 agent_id',
    `target_name` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '冗余目标名称（知识库名称或智能体名称）',
    `sample_count` INT NOT NULL DEFAULT 0 COMMENT '样本总数',
    `content_json` MEDIUMTEXT NOT NULL COMMENT '样本集 JSON 数组：[{"id":"s1","query":"...","groundTruth":"...","expectedDocNames":["..."],"expectedKeywords":["..."],"expectedTools":["..."]}]',
    `description` VARCHAR(255) DEFAULT '' COMMENT '评测集描述说明',
    `add_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_eval_ds_space_type` (`space_id`, `eval_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI评测数据集表';

-- 2. 评测实验记录表
CREATE TABLE IF NOT EXISTS `xxl_ai_eval_experiment` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键自增',
    `space_id` BIGINT NOT NULL COMMENT '所属业务空间ID（多租户隔离）',
    `dataset_id` BIGINT NOT NULL COMMENT '关联评测集ID',
    `name` VARCHAR(100) NOT NULL COMMENT '实验名称（如：RRF权重优化对比测试-01）',
    `eval_type` VARCHAR(20) NOT NULL DEFAULT 'RAG' COMMENT '评测类型：RAG / AGENT',
    `target_id` BIGINT NOT NULL DEFAULT 0 COMMENT '评估目标ID',
    `target_name` VARCHAR(100) NOT NULL DEFAULT '' COMMENT '评估目标名称',
    `judge_model_id` BIGINT DEFAULT NULL COMMENT '裁判大模型ID（关联 xxl_ai_supplier_model.id，为空使用空间默认模型）',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '实验状态：0-待运行, 1-运行中, 2-已完成, 3-失败, 4-已取消',
    `total_cases` INT NOT NULL DEFAULT 0 COMMENT '总测试用例数',
    `completed_cases` INT NOT NULL DEFAULT 0 COMMENT '已完成用例数',
    `avg_hit_rate` DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '平均检索命中率 HitRate@3（0.0000~1.0000）',
    `avg_mrr` DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '平均倒数排名 MRR（0.0000~1.0000）',
    `avg_faithfulness` DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '平均上下文忠实度 / 防幻觉得分（0.0000~1.0000，LLM Judge）',
    `avg_relevance` DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '平均答案相关性得分（0.0000~1.0000，LLM Judge）',
    `avg_goal_completion` DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '平均目标达成率（0.0000~1.0000，仅 AGENT 类型生效）',
    `avg_latency_ms` INT NOT NULL DEFAULT 0 COMMENT '单用例平均耗时（毫秒）',
    `total_tokens` INT NOT NULL DEFAULT 0 COMMENT '实验累计消耗 Token 数',
    `summary_json` MEDIUMTEXT DEFAULT NULL COMMENT '实验汇总指标快照及逐用例明细结果 JSON（包含各 case 的 Query、切片、回复、得分与归因）',
    `error_msg` VARCHAR(500) DEFAULT '' COMMENT '异常失败信息',
    `add_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_eval_exp_space_ds` (`space_id`, `dataset_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI评测实验记录表';
