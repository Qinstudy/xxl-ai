package com.xxl.ai.api.business.harness.memory;

import com.xxl.ai.api.business.agent.mapper.AgentMapper;
import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.memory.mapper.MemoryMapper;
import com.xxl.ai.api.business.memory.model.entity.Memory;
import com.xxl.ai.api.business.session.mapper.SessionMapper;
import com.xxl.ai.api.business.session.mapper.SessionMsgMapper;
import com.xxl.ai.api.business.session.model.entity.SessionMsg;
import com.xxl.ai.api.framework.model.entity.Config;
import com.xxl.ai.api.framework.service.ConfigService;
import com.xxl.tool.concurrent.CyclicThread;
import com.xxl.tool.core.CollectionTool;
import com.xxl.tool.core.StringTool;
import com.xxl.tool.id.UUIDTool;
import com.xxl.tool.response.Response;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 长期记忆归档工具（harness 运行时）
 *
 * 【归档流程】
 *  1、调度：{@link CyclicThread} 按固定心跳（{@value #TICK_MILLIS}ms）轮询；
 *  2、竞争：归档前以 Redis SETNX 抢锁（锁 TTL = 心跳间隔，仅作互斥），抢不到直接忽略；抢到则归档，处理完立即解锁；
 *  3、检测：遍历开启记忆且已发布的 Agent，对其下每个访客，只判断「是否存在比水位线 last_msg_id 更新的消息」，
 *     有则取水位线之后的新消息归档，无则跳过；
 *  4、生成：取「新消息 + 历史记忆」经模型按固定维度生成最新记忆（定长），按唯一键 upsert 并推进水位线 last_msg_id。
 *
 * 【边界】本工具为对话域运行时能力，无 Controller；记忆的元数据 CRUD 在 {@code business/memory}。
 *
 * @author xxl-ai 2026-10-17
 */
@Component
public class MemoryArchiveTool implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(MemoryArchiveTool.class);

    /** 归档调度心跳(ms)：CyclicThread 固定心跳 */
    private static final long TICK_MILLIS = 60_000L;
    /** 归档锁 key */
    private static final String LOCK_KEY = "xxl:ai:memory:archive:lock";

    /** 配置key：记忆内容最大长度 */
    private static final String KEY_MAX_LEN = "memory.content.max.length";

    /** 默认记忆长度上限（字符） */
    private static final int DEFAULT_MAX_LEN = 2000;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ConfigService configService;
    @Resource
    private AgentMapper agentMapper;
    @Resource
    private SessionMapper sessionMapper;
    @Resource
    private SessionMsgMapper sessionMsgMapper;
    @Resource
    private MemoryMapper memoryMapper;
    @Resource
    private MemoryTool memoryTool;

    /** 实例标识（锁持有者） */
    private final String instanceId = UUIDTool.getSimpleUUID();
    private volatile boolean running = false;
    private CyclicThread cyclicThread;

    // ==================== 生命周期 ====================

    /**
     * 启动：拉起 CyclicThread 周期性尝试归档
     */
    @Override
    public void start() {
        running = true;
        cyclicThread = new CyclicThread("xxl-ai-memory-archive", true, this::tick, TICK_MILLIS);
        cyclicThread.start();
        logger.info("长期记忆：归档线程启动, instanceId={}", instanceId);
    }

    /**
     * 停止：中断归档线程
     */
    @Override
    public void stop() {
        running = false;
        if (cyclicThread != null) {
            cyclicThread.stop();
        }
        logger.info("长期记忆：归档线程停止, instanceId={}", instanceId);
    }

    /**
     * 返回运行状态（SmartLifecycle 约定）
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    // ==================== 调度与竞争 ====================

    /**
     * 心跳：以 Redis 锁竞争归档权，抢到才归档并处理完立即解锁，抢不到直接忽略
     *
     * 锁仅作互斥（TTL = 心跳间隔，进程异常时到期自动释放），不承担归档节奏。
     */
    private void tick() {
        if (!running) {
            return;
        }

        // 抢锁：TTL 取心跳间隔，抢不到直接忽略
        Boolean locked = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY, instanceId, TICK_MILLIS, TimeUnit.MILLISECONDS);
        if (!Boolean.TRUE.equals(locked)) {
            return;
        }
        try {
            archiveOnce();
        } catch (Exception e) {
            logger.error("长期记忆：归档执行异常, err={}", e.getMessage(), e);
        } finally {
            // 处理完立马解锁
            stringRedisTemplate.delete(LOCK_KEY);
        }
    }

    // ==================== 归档 ====================

    /**
     * 扫描开启记忆的 Agent，逐个访客检测并生成记忆
     */
    private void archiveOnce() {

        // 记忆长度上限：超过此长度的记忆会被截断，避免过长
        int maxLen = intConfig(KEY_MAX_LEN, DEFAULT_MAX_LEN);

        // 扫描开启记忆的 Agent，逐个访客检测并生成记忆
        List<Agent> agents = agentMapper.listMemoryEnabled();
        if (CollectionTool.isEmpty(agents)) {
            return;
        }
        for (Agent agent : agents) {
            if (!running) {
                return;
            }
            List<String> visitorIds = sessionMapper.listVisitorIds(agent.getId());
            for (String visitorId : visitorIds) {
                try {
                    archiveOne(agent, visitorId, maxLen);
                } catch (Exception e) {
                    // 单个访客归档失败不影响其他访客
                    logger.warn("长期记忆：归档失败, agentId={}, visitorId={}, err={}", agent.getId(), visitorId, e.getMessage());
                }
            }
        }
    }

    /**
     * 单个 Agent×访客 归档：是否比水位线更新 → 生成 → upsert + 推进水位线
     */
    private void archiveOne(Agent agent, String visitorId, int maxLen) {
        if (StringTool.isBlank(visitorId)) {
            return;
        }

        // 获取水位线 last_msg_id：若无则为 0，表示首次归档
        Memory memory = memoryMapper.loadByAgentVisitor(agent.getId(), visitorId);
        long lastMsgId = memory == null ? 0L : memory.getLastMsgId();

        // 新消息检测：是否存在比水位线 last_msg_id 更新的消息，无则跳过
        Long maxId = sessionMsgMapper.maxIdByAgentVisitor(agent.getId(), visitorId);
        if (maxId == null || maxId <= lastMsgId) {
            return;
        }

        // 新消息：取水位线之后的全部新消息（已完成），按时间正序
        List<SessionMsg> newMsgs = sessionMsgMapper.listByAgentVisitorAfterId(agent.getId(), visitorId, lastMsgId);
        if (CollectionTool.isEmpty(newMsgs)) {
            return;
        }

        // 生成最新记忆：新消息 + 历史记忆 → 模型生成最新记忆（定长）
        String newMemory = memoryTool.generate(agent, memory == null ? null : memory.getContent(), newMsgs, maxLen);
        if (StringTool.isBlank(newMemory)) {
            return;
        }

        // 记忆更新
        Memory save = new Memory();
        save.setAgentId(agent.getId());
        save.setVisitorId(visitorId);
        save.setContent(newMemory);
        save.setLastMsgId(maxId);
        memoryMapper.upsert(save);
        logger.info("长期记忆：归档完成, agentId={}, visitorId={}, lastMsgId={}", agent.getId(), visitorId, maxId);
    }

    // ==================== 配置读取 ====================

    /**
     * 读取整型配置（带本地缓存），缺失或非法时返回默认值
     */
    private int intConfig(String key, int defaultValue) {
        try {
            Response<Config> resp = configService.loadByKeyWithCache(key);
            if (resp != null && resp.isSuccess() && resp.getData() != null
                    && StringTool.isNotBlank(resp.getData().getValue())) {
                return Integer.parseInt(resp.getData().getValue().trim());
            }
        } catch (Exception e) {
            logger.warn("读取配置失败，使用默认值, key={}, err={}", key, e.getMessage());
        }
        return defaultValue;
    }

}
