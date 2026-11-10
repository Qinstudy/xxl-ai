-- --------------------------------------------------
-- XXL-AI 知识库相似度阈值过滤增量升级脚本 (v1.5.0+)
-- 适用场景：存量数据库平滑升级（无需重建表或清空数据）
-- --------------------------------------------------

-- 1、知识库表扩展相似度阈值字段（similarity_threshold）
ALTER TABLE `xxl_ai_knowledge_base`
    ADD COLUMN `similarity_threshold` DECIMAL(4, 2) NOT NULL DEFAULT 0.40 COMMENT '相似度阈值 (0.00-1.00，默认 0.40)' AFTER `top_k`;
