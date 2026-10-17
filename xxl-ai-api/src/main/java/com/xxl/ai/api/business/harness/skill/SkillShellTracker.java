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
 * 同步命令自带超时、会自行结束，无需跟踪。
 *
 * @author xxl-ai 2026-10-17
 */
public class SkillShellTracker implements ExecBackend {

    private final ExecBackend delegate;                             /* 底层执行后端 */
    private final Set<ExecHandle> handles = ConcurrentHashMap.newKeySet();  /* 在册后台进程 */
    private volatile boolean cancelled = false;                     /* 是否已终止 */

    public SkillShellTracker(ExecBackend delegate) {
        this.delegate = delegate;
    }

    @Override
    public ExecResult run(ExecSpec spec) {
        return delegate.run(spec);
    }

    @Override
    public ExecHandle start(ExecSpec spec) {
        if (cancelled) {
            throw new IllegalStateException("会话已终止");
        }
        ExecHandle handle = delegate.start(spec);
        handles.add(handle);
        // 竞争兜底：登记后已被终止，立即回收
        if (cancelled) {
            handles.remove(handle);
            handle.kill();
        }
        return handle;
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

}
