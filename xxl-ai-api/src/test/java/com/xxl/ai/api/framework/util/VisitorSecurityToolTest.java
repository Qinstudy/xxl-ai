package com.xxl.ai.api.framework.util;

import com.xxl.tool.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 访客端 HMAC 签名与防越权工具单元测试（[SEC-001]）
 *
 * @author xxl-ai 2026-11-11
 */
public class VisitorSecurityToolTest {

    private VisitorSecurityTool visitorSecurityTool;

    @BeforeEach
    void setUp() {
        visitorSecurityTool = new VisitorSecurityTool();
        visitorSecurityTool.init();
    }

    @Test
    void testSignAndVerifySuccess() {
        String visitorId = "visitor-12345";
        String token = visitorSecurityTool.sign(visitorId);
        assertNotNull(token);
        assertTrue(visitorSecurityTool.verify(visitorId, token));
    }

    @Test
    void testVerifyTamperedToken() {
        String visitorId = "visitor-12345";
        String token = visitorSecurityTool.sign(visitorId);
        assertNotNull(token);

        // 篡改 Token 尾部字符
        String tampered = token.substring(0, token.length() - 2) + "ab";
        assertFalse(visitorSecurityTool.verify(visitorId, tampered));
    }

    @Test
    void testVerifyMismatchedVisitorId() {
        String visitorId = "visitor-attacker";
        String victimVisitorId = "visitor-victim";
        String token = visitorSecurityTool.sign(victimVisitorId);

        // 攻击者试图用受害者的 token 或自己的身份调用
        assertFalse(visitorSecurityTool.verify(visitorId, token));
    }

    @Test
    void testVerifyBlankOrNull() {
        assertFalse(visitorSecurityTool.verify(null, "some-token"));
        assertFalse(visitorSecurityTool.verify("", "some-token"));
        assertFalse(visitorSecurityTool.verify("visitor-1", null));
        assertFalse(visitorSecurityTool.verify("visitor-1", ""));
        assertFalse(visitorSecurityTool.verify("visitor-1", "invalid-format"));
    }

    @Test
    void testVerifyExpiredToken() {
        String visitorId = "visitor-expired";
        // 构造一个过去时间的 expired token
        long pastTime = System.currentTimeMillis() - 10000;
        String raw = visitorId + "." + pastTime;
        // 计算其合法签名但已超时
        String signature = (String) ReflectionTestUtils.invokeMethod(visitorSecurityTool, "computeHmac", raw);
        String expiredPayload = raw + "." + signature;
        String expiredToken = Base64.getUrlEncoder().withoutPadding().encodeToString(expiredPayload.getBytes(StandardCharsets.UTF_8));

        assertFalse(visitorSecurityTool.verify(visitorId, expiredToken));
    }

    @Test
    void testValidateVisitorWithMockRequest() {
        String visitorId = "visitor-req-1";
        MockHttpServletRequest request = new MockHttpServletRequest();

        // 1. 无 token 请求必须拒绝（彻底封堵空 Token 绕过漏洞）
        Response<String> res1 = visitorSecurityTool.validateVisitor(request, visitorId);
        assertFalse(res1.isSuccess());
        assertEquals("访客凭证无效或已过期", res1.getMsg());

        // 2. 带有效 token 请求头放行
        String token = visitorSecurityTool.sign(visitorId);
        request.addHeader(VisitorSecurityTool.HEADER_VISITOR_TOKEN, token);
        Response<String> res2 = visitorSecurityTool.validateVisitor(request, visitorId);
        assertTrue(res2.isSuccess());

        // 3. 携带伪造篡改的 token 请求头拒绝
        request.removeHeader(VisitorSecurityTool.HEADER_VISITOR_TOKEN);
        request.addHeader(VisitorSecurityTool.HEADER_VISITOR_TOKEN, "fake-malicious-token");
        Response<String> res3 = visitorSecurityTool.validateVisitor(request, visitorId);
        assertFalse(res3.isSuccess());
        assertEquals("访客凭证无效或已过期", res3.getMsg());
    }

    @Test
    void testValidateAndRefreshTokenStrict() {
        String visitorId = "visitor-refresh-1";
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 无 token 时 validateAndRefreshToken 必须拒绝且不得下发凭证
        Response<String> rejected = visitorSecurityTool.validateAndRefreshToken(request, response, visitorId);
        assertFalse(rejected.isSuccess());
        assertNull(response.getHeader(VisitorSecurityTool.HEADER_VISITOR_TOKEN));

        // 携带合法 token 时校验通过并滚动刷新签名
        String token = visitorSecurityTool.sign(visitorId);
        request.addHeader(VisitorSecurityTool.HEADER_VISITOR_TOKEN, token);
        Response<String> result = visitorSecurityTool.validateAndRefreshToken(request, response, visitorId);
        assertTrue(result.isSuccess());

        String issuedToken = response.getHeader(VisitorSecurityTool.HEADER_VISITOR_TOKEN);
        assertNotNull(issuedToken);
        assertTrue(visitorSecurityTool.verify(visitorId, issuedToken));
    }

    @Test
    void testIssueOrRefreshIdentityPreventsHijacking() {
        // 1. 攻击者尝试传受害者 visitorId 但未带匹配 Token：必须忽略并重发全新随机 ID + Token
        String victimId = "victim-user-007";
        MockHttpServletRequest hijackReq = new MockHttpServletRequest();
        MockHttpServletResponse hijackResp = new MockHttpServletResponse();

        String mintedId = visitorSecurityTool.issueOrRefreshIdentity(hijackReq, hijackResp, victimId);
        assertNotNull(mintedId);
        assertNotEquals(victimId, mintedId);
        assertEquals(mintedId, hijackResp.getHeader(VisitorSecurityTool.HEADER_VISITOR_ID));

        String mintedToken = hijackResp.getHeader(VisitorSecurityTool.HEADER_VISITOR_TOKEN);
        assertNotNull(mintedToken);
        assertTrue(visitorSecurityTool.verify(mintedId, mintedToken));
        assertFalse(visitorSecurityTool.verify(victimId, mintedToken));

        // 2. 合法用户携带匹配的 visitorId 与 Token：沿用原 visitorId 并刷新 Token
        MockHttpServletRequest legitimateReq = new MockHttpServletRequest();
        legitimateReq.addHeader(VisitorSecurityTool.HEADER_VISITOR_TOKEN, mintedToken);
        MockHttpServletResponse legitimateResp = new MockHttpServletResponse();

        String confirmedId = visitorSecurityTool.issueOrRefreshIdentity(legitimateReq, legitimateResp, mintedId);
        assertEquals(mintedId, confirmedId);
        assertEquals(mintedId, legitimateResp.getHeader(VisitorSecurityTool.HEADER_VISITOR_ID));
        assertTrue(visitorSecurityTool.verify(mintedId, legitimateResp.getHeader(VisitorSecurityTool.HEADER_VISITOR_TOKEN)));
    }

    @Test
    void testRandomStartupSecretDiffersAcrossInstances() {
        VisitorSecurityTool another = new VisitorSecurityTool();
        another.init();
        String tokenFromFirst = visitorSecurityTool.sign("visitor-x");
        // 未配置共享 secret 时，两实例启动各自随机生成密钥，互相无法伪造验签
        assertFalse(another.verify("visitor-x", tokenFromFirst));
    }

    @Test
    void testResolveTokenFromQueryParam() {
        String visitorId = "visitor-param-1";
        String token = visitorSecurityTool.sign(visitorId);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter(VisitorSecurityTool.PARAM_VISITOR_TOKEN, token);

        assertEquals(token, visitorSecurityTool.resolveToken(request));
        assertTrue(visitorSecurityTool.validateVisitor(request, visitorId).isSuccess());
    }
}
