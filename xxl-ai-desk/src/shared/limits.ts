/*
 * 上下文与记忆的规模上限：主进程 / 渲染层共用，避免同一数值多处硬编码。
 * @author xuxueli 2026-11-02
 */

/* 对话历史窗口：最多保留的最近消息条数 */
export const HISTORY_MAX_MESSAGES = 50

/* 对话历史窗口：总字符上限（超过后从最旧轮次继续丢弃） */
export const HISTORY_MAX_CHARS = 120_000

/*
 * 记忆生成触发阈值：项目内自上次生成后积压的消息数达到该值即压缩。
 * 与历史窗口保持一致：积压超过窗口，意味着最早的内容即将被挤出上下文。
 */
export const AGENT_MEMORY_MESSAGE_LIMIT = HISTORY_MAX_MESSAGES
