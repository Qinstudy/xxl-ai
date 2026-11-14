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
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * 访客端身份令牌（VisitorToken）HMAC 签名与验签工具（[SEC-001] IDOR 防护）
 *
 * @author xxl-ai 2026-11-11
 */
@Component
public class VisitorSecurityTool {

    private static final Logger logger = LoggerFactory.getLogger(VisitorSecurityTool.class);

    public static final String HEADER_VISITOR_ID = "X-Visitor-Id";
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
            // 未显式配置密钥时生成启动级随机 32 字节密钥（杜绝源码硬编码密钥被公网离线伪造）
            byte[] randomBytes = new byte[32];
            new SecureRandom().nextBytes(randomBytes);
            this.secretKeyBytes = randomBytes;
            logger.warn("未配置 xxl-ai.chat.visitor.secret，已启用单节点随机访客签名密钥；多实例部署或需重启后保持访客会话，请显式配置一致密钥");
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
     * 严格校验 HTTP 请求中的访客身份与签名令牌（禁止无 Token 绕过）
     */
    public Response<String> validateVisitor(HttpServletRequest request, String visitorId) {
        if (StringTool.isBlank(visitorId)) {
            return Response.ofFail("访客标识不能为空");
        }
        String token = resolveToken(request);
        if (StringTool.isBlank(token) || !verify(visitorId, token)) {
            return Response.ofFail("访客凭证无效或已过期");
        }
        return Response.ofSuccess();
    }

    /**
     * 严格验签并在响应头滚动刷新 Token（仅放行已持有合法 Token 的请求）
     */
    public Response<String> validateAndRefreshToken(HttpServletRequest request, HttpServletResponse response, String visitorId) {
        Response<String> check = validateVisitor(request, visitorId);
        if (!check.isSuccess()) {
            return check;
        }
        if (response != null && StringTool.isNotBlank(visitorId)) {
            String freshToken = sign(visitorId.trim());
            response.setHeader(HEADER_VISITOR_TOKEN, freshToken);
        }
        return Response.ofSuccess();
    }

    /**
     * 仅用于 /chat/load 公开握手初始化：
     * 已持有匹配且有效 Token 时沿用原 visitorId 并刷新签名；
     * 未携带或验签失败时，忽略客户端传入的不可信 visitorId，由服务端重新签发全新 UUID 与对应签名（阻断冒用他人 visitorId 骗签）
     */
    public String issueOrRefreshIdentity(HttpServletRequest request, HttpServletResponse response, String clientVisitorId) {
        String token = resolveToken(request);
        String effectiveVisitorId;
        if (StringTool.isNotBlank(clientVisitorId) && StringTool.isNotBlank(token) && verify(clientVisitorId, token)) {
            effectiveVisitorId = clientVisitorId.trim();
        } else {
            effectiveVisitorId = "v_" + UUID.randomUUID().toString().replace("-", "");
        }
        if (response != null) {
            response.setHeader(HEADER_VISITOR_ID, effectiveVisitorId);
            response.setHeader(HEADER_VISITOR_TOKEN, sign(effectiveVisitorId));
        }
        return effectiveVisitorId;
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
