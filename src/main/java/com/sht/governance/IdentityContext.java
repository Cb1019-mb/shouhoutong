package com.sht.governance;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话 → 客户身份注册表(服务端绑定,LLM 不可见不可改)。
 * AgentService 在发起推理前 bind,流结束后 unbind;
 * 工具实现通过 ToolContext 里的 conversationId 解析身份,越权访问在此拦截。
 *
 * 不用 ThreadLocal:Spring AI 的工具回调执行在 reactor 线程上,
 * 与发起请求的 Tomcat 线程不同,ThreadLocal 不可靠。
 */
@Component
public class IdentityContext {

    private final Map<Long, Long> conversationCustomer = new ConcurrentHashMap<>();

    public void bind(Long conversationId, Long customerId) {
        conversationCustomer.put(conversationId, customerId);
    }

    public void unbind(Long conversationId) {
        conversationCustomer.remove(conversationId);
    }

    /** 未绑定视为身份不明 → 上层一律按拒绝处理(default-deny 的身份版) */
    public Long resolve(Long conversationId) {
        return conversationId == null ? null : conversationCustomer.get(conversationId);
    }
}
