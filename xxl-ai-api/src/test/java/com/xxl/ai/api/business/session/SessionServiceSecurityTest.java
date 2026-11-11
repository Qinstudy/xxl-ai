package com.xxl.ai.api.business.session;

import com.xxl.ai.api.business.harness.skill.SkillArtifactTool;
import com.xxl.ai.api.business.session.mapper.SessionMapper;
import com.xxl.ai.api.business.session.mapper.SessionMsgMapper;
import com.xxl.ai.api.business.session.model.entity.Session;
import com.xxl.ai.api.business.session.service.impl.SessionServiceImpl;
import com.xxl.tool.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 会话防越权（IDOR）隔离与访客归属强校验测试（[SEC-001]）
 *
 * @author xxl-ai 2026-11-11
 */
@ExtendWith(MockitoExtension.class)
public class SessionServiceSecurityTest {

    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private SessionMsgMapper sessionMsgMapper;
    @Mock
    private SkillArtifactTool skillArtifactTool;

    @InjectMocks
    private SessionServiceImpl sessionService;

    private Session victimSession;

    @BeforeEach
    void setUp() {
        victimSession = new Session();
        victimSession.setId(100L);
        victimSession.setVisitorId("legitimate-visitor-100");
        victimSession.setTitle("重要商业机密对话");
    }

    @Test
    void testRenameLegitimateOwnerSuccess() {
        when(sessionMapper.load(100L)).thenReturn(victimSession);

        Response<String> res = sessionService.rename(100L, "legitimate-visitor-100", "合法修改标题");
        assertTrue(res.isSuccess());
        verify(sessionMapper, times(1)).updateTitle(100L, "合法修改标题");
    }

    @Test
    void testRenameAttackerIdorBlocked() {
        when(sessionMapper.load(100L)).thenReturn(victimSession);

        // 攻击者传入自己的 visitorId 试图篡改他人会话
        Response<String> res = sessionService.rename(100L, "attacker-visitor-666", "恶意篡改标题");
        assertFalse(res.isSuccess());
        assertEquals("无权操作该对话", res.getMsg());
        verify(sessionMapper, never()).updateTitle(anyLong(), anyString());
    }

    @Test
    void testRenameBlankVisitorIdBlocked() {
        when(sessionMapper.load(100L)).thenReturn(victimSession);

        Response<String> res = sessionService.rename(100L, "", "未携带身份调用");
        assertFalse(res.isSuccess());
        assertEquals("无权操作该对话", res.getMsg());
        verify(sessionMapper, never()).updateTitle(anyLong(), anyString());
    }

    @Test
    void testDeleteAttackerIdorBlocked() {
        when(sessionMapper.load(100L)).thenReturn(victimSession);

        // 攻击者试图删除他人会话
        Response<String> res = sessionService.delete(100L, "attacker-visitor-666");
        assertFalse(res.isSuccess());
        assertEquals("无权操作该对话", res.getMsg());
        verify(sessionMapper, never()).delete(anyLong());
        verify(sessionMsgMapper, never()).deleteBySessionId(anyLong());
    }

    @Test
    void testMsgListAttackerIdorBlocked() {
        when(sessionMapper.load(100L)).thenReturn(victimSession);

        // 攻击者试图越权窃取他人会话消息
        var res = sessionService.msgList(100L, "attacker-visitor-666");
        assertFalse(res.isSuccess());
        assertEquals("无权访问该对话", res.getMsg());
        verify(sessionMsgMapper, never()).listBySessionId(anyLong());
    }

    @Test
    void testRequireVisitorSessionOwnershipCheck() {
        when(sessionMapper.load(100L)).thenReturn(victimSession);

        // 合法访客成功获取
        Session s = sessionService.requireVisitorSession(100L, "legitimate-visitor-100");
        assertNotNull(s);
        assertEquals(100L, s.getId());

        // 攻击者触发异常阻断
        assertThrows(IllegalArgumentException.class, () ->
                sessionService.requireVisitorSession(100L, "attacker-visitor-666")
        );

        // 空访客标识触发异常阻断
        assertThrows(IllegalArgumentException.class, () ->
                sessionService.requireVisitorSession(100L, null)
        );
    }
}
