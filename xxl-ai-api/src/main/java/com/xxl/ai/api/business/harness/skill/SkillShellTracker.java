package com.xxl.ai.api.business.harness.skill;

import org.springaicommunity.agent.common.exec.ExecBackend;
import org.springaicommunity.agent.common.exec.ExecHandle;
import org.springaicommunity.agent.common.exec.ExecResult;
import org.springaicommunity.agent.common.exec.ExecSpec;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill Shell 进程跟踪器（harness 运行时）
 *
 * 包装底层 ExecBackend，登记本次生成启动的后台 Shell 进程；会话终止或生成结束时统一 kill，
 * 避免 ShellTools 实例丢弃后后台进程无人回收。终止后拒绝再启动。
 * 同步命令增加最大超时兜底，防止挂起任务耗尽系统资源。
 *
 * @author xxl-ai 2026-10-17
 */
public class SkillShellTracker implements ExecBackend {

    /** 单条命令最大允许超时时间（120秒，防止死循环/挂起命令耗尽 Worker 线程） */
    public static final long MAX_COMMAND_TIMEOUT_MILLIS = 120_000L;

    private final ExecBackend delegate;                             /* 底层执行后端 */
    private final Set<ExecHandle> handles = ConcurrentHashMap.newKeySet();  /* 在册后台进程 */
    private volatile boolean cancelled = false;                     /* 是否已终止 */

    public SkillShellTracker(ExecBackend delegate) {
        this.delegate = delegate;
    }

    @Override
    public ExecResult run(ExecSpec spec) {
        if (cancelled) {
            throw new IllegalStateException("会话已终止");
        }
        return delegate.run(boundSpec(spec));
    }

    @Override
    public ExecHandle start(ExecSpec spec) {
        if (cancelled) {
            throw new IllegalStateException("会话已终止");
        }
        ExecHandle handle = delegate.start(boundSpec(spec));
        handles.add(handle);
        // 竞争兜底：登记后已被终止，立即回收
        if (cancelled) {
            handles.remove(handle);
            handle.kill();
        }
        return handle;
    }

    /**
     * 规范化并约束命令超时时间，防止未设超时或超长超时
     */
    public static ExecSpec boundSpec(ExecSpec spec) {
        if (spec == null) {
            return null;
        }
        long timeout = spec.timeoutMillis();
        if (timeout <= 0 || timeout > MAX_COMMAND_TIMEOUT_MILLIS) {
            return new ExecSpec(spec.command(), MAX_COMMAND_TIMEOUT_MILLIS, spec.env());
        }
        return spec;
    }

    /**
     * 终止并回收全部在册后台 Shell 进程（幂等）
     */
    public void cancel() {
        cancelled = true;
        for (ExecHandle handle : handles) {
            try {
                handle.kill();
            } catch (Exception ignored) {
            }
        }
        handles.clear();
    }

    public boolean isCancelled() {
        return cancelled;
    }

}
