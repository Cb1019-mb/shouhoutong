package com.sht.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 会话 → SSE 连接注册表。
 * 职责:① 模拟器/工作台订阅流式回复;② 供后续审批回调线程按会话主动推送(避坑②的推送通道)。
 * token 段逐条 send + flush(SseEmitter.send 即发即推,避免被缓冲成"一次性吐完")。
 */
@Slf4j
@Service
public class SseService {

    private final Map<Long, List<SseEmitter>> registry = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long conversationId) {
        SseEmitter emitter = new SseEmitter(0L); // 不超时,由页面生命周期管理
        List<SseEmitter> list = registry.computeIfAbsent(conversationId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("open").data("connected"));
        } catch (IOException e) {
            remove.run();
        }
        return emitter;
    }

    public void sendToken(Long conversationId, String token) {
        broadcast(conversationId, "token", token);
    }

    /** complete 事件携带完整回复文本:客户端漏收 token 时可整体兜底 */
    public void sendComplete(Long conversationId, String fullText) {
        broadcast(conversationId, "complete", fullText);
    }

    /** 审批结果等异步消息主动推送(W3):独立 message 事件,模拟器实时追加 */
    public void pushMessage(Long conversationId, String text) {
        broadcast(conversationId, "message", text);
    }

    /**
     * 心跳:保活连接防代理掐断空闲 SSE,同时清除断开的 emitter(原缺陷:断开要等下次发送失败才被发现)。
     * 客户端无 ping 监听器,事件静默忽略。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 30000)
    public void heartbeat() {
        for (Map.Entry<Long, List<SseEmitter>> entry : registry.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().name("ping").data("1"));
                } catch (Exception e) {
                    entry.getValue().remove(emitter);
                }
            }
        }
    }

    private void broadcast(Long conversationId, String event, String data) {
        List<SseEmitter> list = registry.get(conversationId);
        if (list == null || list.isEmpty()) {
            return; // 无订阅者也不丢消息:最终消息仍会落库,前端拉历史可见
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data, MediaType.TEXT_PLAIN));
            } catch (Exception e) {
                list.remove(emitter);
            }
        }
    }
}
