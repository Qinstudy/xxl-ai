package com.xxl.ai.api.business.chat;

import com.xxl.ai.api.business.agent.mapper.AgentMapper;
import com.xxl.ai.api.business.chat.service.impl.ChatServiceImpl;
import com.xxl.ai.api.business.harness.chat.ChatStreamTool;
import com.xxl.ai.api.business.session.mapper.SessionMapper;
import com.xxl.ai.api.business.session.mapper.SessionMsgMapper;
import com.xxl.ai.api.business.session.model.entity.Session;
import com.xxl.ai.api.business.session.model.entity.SessionMsg;
import com.xxl.ai.api.business.session.service.SessionService;
import com.xxl.tool.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ChatService 公开端防越权（resume/stop 强制非空 visitorId 与归属校验）单元测试（[SEC-001]）
 *
 * @author xxl-ai 2026-11-12
 */
@ExtendWith(MockitoExtension.class)
public class ChatServiceSecurityTest {

    @Mock
    private AgentMapper agentMapper;
    @Mock
    private SessionService sessionService;
    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private SessionMsgMapper sessionMsgMapper;
    @Mock
    private ChatStreamTool chatStreamTool;

    @InjectMocks
    private ChatServiceImpl chatService;

    private Session ownedSession;
    private SessionMsg ownedMsg;

    @BeforeEach
    void setUp() {
        ownedSession = new Session();
        ownedSession.setId(55L);
        ownedSession.setVisitorId("owner-visitor");

        ownedMsg = new SessionMsg();
        ownedMsg.setId(88L);
        ownedMsg.setSessionId(55L);
    }

    @Test
    void testStopRejectsBlankOrMismatchedVisitorId() {
        Response<String> blankResp = chatService.stop(55L, "");
        assertFalse(blankResp.isSuccess());
        assertEquals("访客标识不能为空", blankResp.getMsg());
        verify(chatStreamTool, never()).cancel(anyLong());

        when(sessionMapper.load(55L)).thenReturn(ownedSession);
        Response<String> hijackResp = chatService.stop(55L, "attacker-visitor");
        assertFalse(hijackResp.isSuccess());
        assertEquals("无权操作该会话", hijackResp.getMsg());
        verify(chatStreamTool, never()).cancel(anyLong());

        Response<String> okResp = chatService.stop(55L, "owner-visitor");
        assertTrue(okResp.isSuccess());
        verify(chatStreamTool, times(1)).cancel(55L);
    }

    @Test
    void testResumeRejectsBlankOrMismatchedVisitorId() {
        SseEmitter errorEmitter = new SseEmitter();
        when(chatStreamTool.error(anyString())).thenReturn(errorEmitter);

        SseEmitter blankEmitter = chatService.resume(88L, "  ", null);
        assertSame(errorEmitter, blankEmitter);
        verify(chatStreamTool, never()).open(anyLong(), any());

        when(sessionMsgMapper.load(88L)).thenReturn(ownedMsg);
        when(sessionMapper.load(55L)).thenReturn(ownedSession);
        SseEmitter hijackEmitter = chatService.resume(88L, "attacker-visitor", null);
        assertSame(errorEmitter, hijackEmitter);
        verify(chatStreamTool, never()).open(anyLong(), any());

        SseEmitter okEmitter = new SseEmitter();
        when(chatStreamTool.open(88L, "1-0")).thenReturn(okEmitter);
        assertSame(okEmitter, chatService.resume(88L, "owner-visitor", "1-0"));
    }
}
