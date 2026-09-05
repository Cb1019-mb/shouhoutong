package com.sht.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sht.agent.AgentService;
import com.sht.chat.entity.Conversation;
import com.sht.chat.entity.Customer;
import com.sht.chat.entity.Message;
import com.sht.chat.mapper.ConversationMapper;
import com.sht.chat.mapper.CustomerMapper;
import com.sht.chat.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * W1 会话/消息接口(客户模拟器消费)。
 * W2 在此增加身份绑定(会话创建时服务端锁定 customer_id)。
 */
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final CustomerMapper customerMapper;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final SseService sseService;
    private final AgentService agentService;

    public record CreateConversationRequest(Long customerId) {}

    @GetMapping("/api/customers")
    public List<Customer> customers() {
        return customerMapper.selectList(null);
    }

    /** 某客户的历史会话(模拟器"继续历史会话"用,新→旧取 20 条) */
    @GetMapping("/api/customers/{customerId}/conversations")
    public List<Map<String, Object>> customerConversations(@PathVariable Long customerId) {
        return conversationMapper.selectList(new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getCustomerId, customerId)
                        .orderByDesc(Conversation::getId)
                        .last("limit 20"))
                .stream()
                .map(c -> Map.<String, Object>of(
                        "conversationId", c.getId(),
                        "createdAt", c.getCreatedAt() == null ? "" : c.getCreatedAt().toString()))
                .toList();
    }

    @PostMapping("/api/conversations")
    public Map<String, Object> createConversation(@RequestBody CreateConversationRequest req) {
        Customer customer = customerMapper.selectById(req.customerId());
        if (customer == null) {
            throw new IllegalArgumentException("客户不存在: " + req.customerId());
        }
        Conversation created = new Conversation();
        created.setCustomerId(customer.getId());
        created.setStatus("ACTIVE");
        created.setCreatedAt(java.time.LocalDateTime.now());
        conversationMapper.insert(created); // insert 后 id 回填到 created
        return Map.of("conversationId", created.getId(), "customerId", customer.getId(),
                "customerName", customer.getName());
    }

    /** SSE 订阅:进入会话页面即建立,token 从这里流式到达 */
    @GetMapping(value = "/sse/{conversationId}", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@PathVariable Long conversationId) {
        return sseService.subscribe(conversationId);
    }

    public record SendMessageRequest(String content) {}

    @PostMapping("/api/conversations/{id}/messages")
    public ResponseEntity<Map<String, Object>> sendMessage(@PathVariable Long id,
                                                           @RequestBody SendMessageRequest req) {
        Conversation conv = conversationMapper.selectById(id);
        if (conv == null) {
            return ResponseEntity.status(404).body(Map.of("error", "会话不存在"));
        }
        String content = req.content() == null ? "" : req.content().trim();
        if (content.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "消息不能为空"));
        }
        messageMapper.insert(Message.of(id, "user", content));
        // 流式生成是异步订阅,此处立即返回;token 走 /sse/{id}
        agentService.reply(id);
        return ResponseEntity.accepted().body(Map.of("conversationId", id, "status", "streaming"));
    }

    @GetMapping("/api/conversations/{id}/messages")
    public List<Map<String, Object>> history(@PathVariable Long id) {
        return messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, id)
                .orderByAsc(Message::getCreatedAt))
                .stream()
                .map(m -> Map.<String, Object>of("role", m.getRole(), "content", m.getContent()))
                .toList();
    }
}
