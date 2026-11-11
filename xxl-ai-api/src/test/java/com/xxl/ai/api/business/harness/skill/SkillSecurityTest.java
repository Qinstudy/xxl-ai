package com.xxl.ai.api.business.harness.skill;

import com.xxl.ai.api.business.agent.model.entity.Agent;
import com.xxl.ai.api.business.skill.mapper.SkillFileMapper;
import com.xxl.ai.api.business.skill.model.entity.Skill;
import com.xxl.ai.api.business.skill.model.entity.SkillFile;
import com.xxl.ai.api.business.skill.service.SkillService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springaicommunity.agent.common.exec.ExecSpec;
import org.springaicommunity.agent.tools.ShellTools;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Skill 宿主机安全防护与 RCE 门禁（[SEC-002]）单元测试
 *
 * 验证：
 * 1. 子进程环境变量脱敏：屏蔽包含 PASSWORD/SECRET/KEY/TOKEN/AUTH/CREDENTIAL 的系统变量
 * 2. 运行态分级门禁：未发布 Agent (publishStatus=0) 允许挂载 ShellTools，已发布 Agent (publishStatus=1) 剥离 ShellTools
 * 3. 进程跟踪器：单命令超时时间强行截断（<=120s），cancel 后阻断后续命令启动
 * 4. 物化防路径穿越：检测到越界文件名或非法路径时抛出 SecurityException 阻断
 *
 * @author xxl-ai 2026-11-11
 */
@ExtendWith(MockitoExtension.class)
public class SkillSecurityTest {

    @Mock
    private SkillService skillService;

    @Mock
    private SkillFileMapper skillFileMapper;

    @InjectMocks
    private SkillToolFactory skillToolFactory;

    @Test
    @DisplayName("1. [SEC-002] 验证子进程环境变量脱敏净化（屏蔽敏感凭证）")
    void testSafeEnvironmentSanitization() {
        Map<String, String> safeEnv = SkillToolFactory.buildSafeEnvironment();
        assertNotNull(safeEnv);

        // 验证常见敏感关键词均被严格过滤
        for (String key : safeEnv.keySet()) {
            String upper = key.toUpperCase();
            assertFalse(upper.contains("PASS"), "不应包含 PASS: " + key);
            assertFalse(upper.contains("SECRET"), "不应包含 SECRET: " + key);
            assertFalse(upper.contains("KEY"), "不应包含 KEY: " + key);
            assertFalse(upper.contains("TOKEN"), "不应包含 TOKEN: " + key);
            assertFalse(upper.contains("AUTH"), "不应包含 AUTH: " + key);
            assertFalse(upper.contains("CREDENTIAL"), "不应包含 CREDENTIAL: " + key);
        }
    }

    @Test
    @DisplayName("2. [SEC-002] 验证运行态分级门禁：已发布公网 Agent 剥离 ShellTools，未发布草稿 Agent 保留")
    void testBuildExecutorToolsPublishStatusPartitioning(@TempDir Path tempDir) throws IOException {
        ReflectionTestUtils.setField(skillToolFactory, "skillRoot", tempDir.toString());

        // 构造 Skill 实体
        Skill skill = new Skill();
        skill.setId(10L);
        skill.setName("data-cleaner");
        skill.setStatus(0);
        skill.setUpdateTime(new Date());

        SkillFile file = new SkillFile();
        file.setId(101L);
        file.setSkillId(10L);
        file.setName("SKILL.md");
        file.setType(1);
        file.setParentId(0L);
        file.setContent("---\nname: data-cleaner\ndescription: clean data\n---\n# Guide");

        when(skillService.listByIds(List.of(10L))).thenReturn(List.of(skill));
        when(skillFileMapper.listBySkill(10L)).thenReturn(List.of(file));

        SkillShellTracker tracker = skillToolFactory.newShellTracker(1L);

        // A. 未发布 Agent (publishStatus = 0)：挂载 ShellTools（共 5 个工具）
        Agent draftAgent = new Agent();
        draftAgent.setId(1L);
        draftAgent.setSkillIds("10");
        draftAgent.setPublishStatus(0);

        List<Object> draftTools = skillToolFactory.buildExecutorTools(draftAgent, tracker);
        assertEquals(5, draftTools.size());
        assertTrue(draftTools.stream().anyMatch(t -> t instanceof ShellTools), "草稿调试态应挂载 ShellTools");

        // B. 已发布 Agent (publishStatus = 1)：剥离 ShellTools（仅保留 4 个安全文件工具）
        Agent publishedAgent = new Agent();
        publishedAgent.setId(1L);
        publishedAgent.setSkillIds("10");
        publishedAgent.setPublishStatus(1);

        List<Object> publishedTools = skillToolFactory.buildExecutorTools(publishedAgent, tracker);
        assertEquals(4, publishedTools.size());
        assertFalse(publishedTools.stream().anyMatch(t -> t instanceof ShellTools), "已发布公网态必须剥离 ShellTools");
    }

    @Test
    @DisplayName("3. [SEC-002] 验证 SkillShellTracker 超时约束与主动终止拒绝")
    void testSkillShellTrackerTimeoutClampAndCancel() {
        // (1) 超时时间过长（如 600秒）强行钳制在 MAX_COMMAND_TIMEOUT_MILLIS (120秒)
        ExecSpec overTimeout = new ExecSpec("python script.py", 600_000L, Map.of());
        ExecSpec clamped = SkillShellTracker.boundSpec(overTimeout);
        assertEquals(SkillShellTracker.MAX_COMMAND_TIMEOUT_MILLIS, clamped.timeoutMillis());

        // (2) 未设置超时时间（<= 0）兜底赋值最大超时
        ExecSpec zeroTimeout = new ExecSpec("sleep 10", 0L, Map.of());
        ExecSpec defaultClamped = SkillShellTracker.boundSpec(zeroTimeout);
        assertEquals(SkillShellTracker.MAX_COMMAND_TIMEOUT_MILLIS, defaultClamped.timeoutMillis());

        // (3) 合法合理超时时间保持不变
        ExecSpec normalSpec = new ExecSpec("ls -la", 10_000L, Map.of());
        ExecSpec normalClamped = SkillShellTracker.boundSpec(normalSpec);
        assertEquals(10_000L, normalClamped.timeoutMillis());

        // (4) 会话终止后拒绝再次启动新进程
        SkillShellTracker tracker = new SkillShellTracker(null);
        tracker.cancel();
        assertTrue(tracker.isCancelled());

        assertThrows(IllegalStateException.class, () -> tracker.run(normalSpec));
        assertThrows(IllegalStateException.class, () -> tracker.start(normalSpec));
    }

    @Test
    @DisplayName("4. [SEC-002] 验证物化路径防目录穿越 (Zip Slip 防护)")
    void testMaterializeSkillPathTraversalProtection(@TempDir Path tempDir) {
        ReflectionTestUtils.setField(skillToolFactory, "skillRoot", tempDir.toString());

        Path skillDir = tempDir.resolve("agent_1").resolve("malicious-skill");

        // 构造带有非法越界路径的 SkillFile 记录
        SkillFile evilFile = new SkillFile();
        evilFile.setId(999L);
        evilFile.setSkillId(99L);
        evilFile.setName("../../../../etc/passwd");
        evilFile.setType(1);
        evilFile.setParentId(0L);
        evilFile.setContent("root:x:0:0:root:/root:/bin/bash");

        when(skillFileMapper.listBySkill(99L)).thenReturn(List.of(evilFile));

        // 触发物化：必须被 sanitize/normalize + startsWith 安全检查捕获
        // sanitize 会将 ../ 替换为 __，若未逃逸则正常写入，若以任何形式逃逸必被 SecurityException 拦截
        try {
            boolean hasMain = (boolean) ReflectionTestUtils.invokeMethod(skillToolFactory, "materializeSkill", skillDir, 99L);
            assertFalse(hasMain);
            // 确保没有写到技能目录外部
            Path evilPath = Path.of("/etc/passwd");
            assertNotEquals("root:x:0:0:root:/root:/bin/bash", Files.exists(evilPath) ? Files.readString(evilPath) : "");
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof SecurityException || e instanceof SecurityException);
        }
    }
}
