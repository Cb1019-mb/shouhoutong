package com.sht.approval.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 飞书卡片 HTTP 实现:tenant_access_token + im/v1/messages 交互卡片。
 * 需配置 feishu.enabled=true 及 app-id/app-secret(自建应用,店铺主为收件人)。
 * 按钮回调因家用无公网 IP,生产走飞书 WebSocket 长连接 SDK(lark-oapi),
 * 回调进来后调用 ApprovalFlowService.decide() 即可——本类只负责"推出去"。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "feishu.enabled", havingValue = "true")
public class HttpFeishuCardService implements FeishuCardService {

    private static final String OPEN_API = "https://open.feishu.cn/open-apis";

    private final RestClient restClient;
    private final String appId;
    private final String appSecret;
    private final String ownerOpenId;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile String cachedToken;

    public HttpFeishuCardService(@Value("${feishu.app-id}") String appId,
                                 @Value("${feishu.app-secret}") String appSecret,
                                 @Value("${feishu.owner-open-id}") String ownerOpenId) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.ownerOpenId = ownerOpenId;
        this.restClient = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()))
                .build();
    }

    @Override
    public String sendRefundCard(Long taskId, Map<String, Object> preview) {
        String md = "**订单:**" + preview.get("orderNo") + "  **商品:**" + preview.get("product")
                + "\n**客户:**" + preview.get("customerName")
                + "  **金额:**¥" + preview.get("amount")
                + "\n**状态:**" + preview.get("currentStatus") + " → " + preview.get("targetStatus")
                + "\n**类型:**" + preview.get("refundType")
                + "\n**原因:**" + preview.get("reason")
                + "\n**提示:**" + preview.get("planHint");
        Map<String, Object> textDiv = Map.of("tag", "div", "text", Map.of("tag", "lark_md", "content", md));
        Map<String, Object> approveBtn = Map.of(
                "tag", "button",
                "text", Map.of("tag", "plain_text", "content", "批准"),
                "type", "primary",
                "value", Map.of("action", "APPROVED", "taskId", String.valueOf(taskId)));
        Map<String, Object> rejectBtn = Map.of(
                "tag", "button",
                "text", Map.of("tag", "plain_text", "content", "拒绝"),
                "type", "danger",
                "value", Map.of("action", "REJECTED", "taskId", String.valueOf(taskId)));
        Map<String, Object> actions = Map.of("tag", "action", "actions", java.util.List.of(approveBtn, rejectBtn));

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("config", Map.of("wide_screen_mode", true));
        content.put("header", Map.of("title", Map.of("tag", "plain_text", "content", "退款审批 #" + taskId),
                "template", "orange"));
        content.put("elements", java.util.List.of(textDiv, actions));
        Map<String, Object> body = Map.of(
                "receive_id", ownerOpenId,
                "msg_type", "interactive",
                "content", toJson(content),
                "uuid", "sht-approval-" + taskId);
        try {
            String resp = restClient.post()
                    .uri(OPEN_API + "/im/v1/messages?receive_id_type=open_id")
                    .header("Authorization", "Bearer " + token())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .body(toJson(body))
                    .retrieve()
                    .body(String.class);
            log.info("飞书审批卡片已发送 taskId={} resp={}", taskId, abbreviate(resp));
            return extractString(resp, "message_id");
        } catch (Exception e) {
            log.warn("飞书卡片发送异常:{}", e.getMessage());
            return null;
        }
    }

    @Override
    public void updateCard(String feishuMsgId, String statusText, String decider) {
        if (feishuMsgId == null) {
            return;
        }
        try {
            Map<String, Object> content = Map.of(
                    "config", Map.of("wide_screen_mode", true),
                    "elements", java.util.List.of(
                            Map.of("tag", "div", "text", Map.of("tag", "lark_md",
                                    "content", "**审批结果:" + statusText + "**\n处理人:" + decider))));
            restClient.patch()
                    .uri(OPEN_API + "/im/v1/messages/" + feishuMsgId)
                    .header("Authorization", "Bearer " + token())
                    .header("Content-Type", "application/json; charset=utf-8")
                    .body(toJson(Map.of("content", toJson(content))))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.warn("飞书卡片更新失败(不影响审批结果):{}", e.getMessage());
        }
    }

    private String token() {
        if (cachedToken != null) {
            return cachedToken;
        }
        synchronized (this) {
            if (cachedToken == null) {
                String resp = restClient.post()
                        .uri(OPEN_API + "/auth/v3/tenant_access_token/internal")
                        .header("Content-Type", "application/json; charset=utf-8")
                        .body(toJson(Map.of("app_id", appId, "app_secret", appSecret)))
                        .retrieve()
                        .body(String.class);
                cachedToken = extractString(resp, "tenant_access_token");
            }
            return cachedToken;
        }
    }

    /** 用 Jackson 解析,替代原 indexOf 手写解析(字段顺序/引号转义变化不再脆弱) */
    private String extractString(String json, String key) {
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json).get(key);
            return node == null || node.isNull() ? null : node.asText();
        } catch (Exception e) {
            log.warn("飞书响应 JSON 解析失败: {}", abbreviate(json));
            return null;
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : (s.length() <= 200 ? s : s.substring(0, 200));
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
