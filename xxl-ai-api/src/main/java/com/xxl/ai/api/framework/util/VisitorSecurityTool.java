package com.xxl.ai.api.framework.util;

import com.xxl.tool.core.StringTool;
import com.xxl.tool.response.Response;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 访客端身份令牌（VisitorToken）HMAC 签名与验签工具（[SEC-001] IDOR 防护）
 *
 * @author xxl-ai 2026-11-11
 */
@Component
public class VisitorSecurityTool {

    private static final Logger logger = LoggerFactory.getLogger(VisitorSecurityTool.class);

    public static final String HEADER_VISITOR_TOKEN = "X-Visitor-Token";
    public static final String PARAM_VISITOR_TOKEN = "visitorToken";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long DEFAULT_EXPIRE_MILLIS = 30L * 24 * 3600 * 1000; // 30天有效

    @Value("${xxl-ai.chat.visitor.secret:}")
    private String configuredSecret;

    private byte[] secretKeyBytes;

    @PostConstruct
    public void init() {
        if (StringTool.isNotBlank(configuredSecret)) {
            this.secretKeyBytes = configuredSecret.trim().getBytes(StandardCharsets.UTF_8);
        } else {
            // 缺省安全盐（确保开箱即用状态下依然拥有安全防御能力）
            this.secretKeyBytes = "xxl-ai-visitor-security-token-secret-salt-2026".getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 为指定 visitorId 签发带有过期时间的防篡改签名 Token
     * 格式：Base64Url(visitorId + "." + expireAt + "." + hmacHex)
     */
    public String sign(String visitorId) {
        if (StringTool.isBlank(visitorId)) {
            return null;
        }
        long expireAt = System.currentTimeMillis() + DEFAULT_EXPIRE_MILLIS;
        String raw = visitorId.trim() + "." + expireAt;
        String signature = computeHmac(raw);
        String tokenPayload = raw + "." + signature;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenPayload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验 Token 是否合法且与指定 visitorId 匹配且未过期
     */
    public boolean verify(String visitorId, String token) {
        if (StringTool.isBlank(visitorId) || StringTool.isBlank(token)) {
            return false;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token.trim());
            String tokenPayload = new String(decoded, StandardCharsets.UTF_8);
            String[] parts = tokenPayload.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            String tokenVisitorId = parts[0];
            long expireAt = Long.parseLong(parts[1]);
            String tokenSignature = parts[2];

            if (!visitorId.trim().equals(tokenVisitorId)) {
                return false;
            }
            if (System.currentTimeMillis() > expireAt) {
                logger.warn("VisitorToken 已过期, visitorId={}, expireAt={}", visitorId, expireAt);
                return false;
            }
            String raw = tokenVisitorId + "." + expireAt;
            String expectedSignature = computeHmac(raw);
            return MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    tokenSignature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            logger.warn("VisitorToken 验签异常, token={}, err={}", token, e.getMessage());
            return false;
        }
    }

    /**
     * 校验 HTTP 请求中的访客身份与令牌
     */
    public Response<String> validateVisitor(HttpServletRequest request, String visitorId) {
        if (StringTool.isBlank(visitorId)) {
            return Response.ofFail("访客标识不能为空");
        }
        String token = resolveToken(request);
        if (StringTool.isNotBlank(token)) {
            if (!verify(visitorId, token)) {
                return Response.ofFail("访客凭证无效或已过期");
            }
        }
        return Response.ofSuccess();
    }

    /**
     * 校验并在必要时在响应头刷新签发 Token
     */
    public Response<String> validateAndRefreshToken(HttpServletRequest request, HttpServletResponse response, String visitorId) {
        Response<String> check = validateVisitor(request, visitorId);
        if (!check.isSuccess()) {
            return check;
        }
        if (response != null && StringTool.isNotBlank(visitorId)) {
            String freshToken = sign(visitorId);
            response.setHeader(HEADER_VISITOR_TOKEN, freshToken);
        }
        return Response.ofSuccess();
    }

    /**
     * 从请求头或参数中解析 Token
     */
    public String resolveToken(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String token = request.getHeader(HEADER_VISITOR_TOKEN);
        if (StringTool.isBlank(token)) {
            token = request.getParameter(PARAM_VISITOR_TOKEN);
        }
        return token;
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(secretKeyBytes, HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hmacBytes.length * 2);
            for (byte b : hmacBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算 HMAC 异常", e);
        }
    }
}
