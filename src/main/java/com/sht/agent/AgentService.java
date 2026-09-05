package com.sht.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sht.chat.SseService;
import com.sht.chat.entity.Conversation;
import com.sht.chat.entity.Message;
import com.sht.chat.mapper.ConversationMapper;
import com.sht.chat.mapper.MessageMapper;
import com.sht.governance.IdentityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * W2:工具调用版。发起推理前做两件事:
 * ① IdentityContext 绑定会话身份(服务端注入,LLM 不可见);
 * ② toolContext 携带 conversationId 供工具解析身份。
 * 流结束后解绑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final ChatClient chatClient;
    private final MessageMapper messageMapper;
    private final ConversationMapper conversationMapper;
    private final SseService sseService;
    private final IdentityContext identityContext;
    private final com.sht.settings.SettingsService settingsService;

    private final ClassPathResource systemPromptResource = new ClassPathResource("prompts/system.md");

    /** system prompt 启动时一次读入,避免每条消息一次磁盘 I/O */
    private String systemPrompt;

    @jakarta.annotation.PostConstruct
    void loadPrompt() {
        systemPrompt = loadSystemPrompt();
    }

    public void reply(Long conversationId) {
        Conversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            return;
        }
        identityContext.bind(conversationId, conv.getCustomerId());

        String prompt = systemPrompt;
        // 客服语气附加说明(设置页可调,追加到 system prompt)
        String styleAddon = settingsService.get(com.sht.settings.SettingKey.CHAT_STYLE_ADDON);
        if (styleAddon != null && !styleAddon.isBlank()) {
            prompt = prompt + "\n\n## 店主自定义要求(必须遵守)\n" + styleAddon;
        }
        // 与实体类 Message 重名,Spring AI 的消息类型用全限定名
        List<org.springframework.ai.chat.messages.Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(prompt));

        List<Message> history = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getCreatedAt));
        // 携带历史轮数运行时可调(设置页),兜底 yml 默认并记录读取失败
        int maxHistory;
        try {
            maxHistory = settingsService.getInt(com.sht.settings.SettingKey.CHAT_MAX_HISTORY);
        } catch (Exception e) {
            log.warn("设置 chat.maxHistory 读取失败,使用默认 20", e);
            maxHistory = 20;
        }
        history.stream().skip(Math.max(0, history.size() - maxHistory)).forEach(m -> {
            if ("user".equals(m.getRole())) {
                promptMessages.add(new UserMessage(m.getContent()));
            } else if ("assistant".equals(m.getRole())) {
                promptMessages.add(new AssistantMessage(m.getContent()));
            }
        });

        // 温度运行时可调(设置页),兜底 yml 默认并记录读取失败
        double temperature = 0;
        try {
            temperature = settingsService.getDouble(com.sht.settings.SettingKey.MODEL_TEMPERATURE);
        } catch (Exception e) {
            log.warn("设置 model.temperature 读取失败,使用默认 0", e);
        }

        StringBuilder full = new StringBuilder();
        ThinkFilter thinkFilter = new ThinkFilter();
        chatClient.prompt()
                .messages(promptMessages)
                .options(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .temperature(temperature)
                        .build())
                .toolContext(Map.of("conversationId", conversationId))
                .stream()
                .content()
                .doOnNext(token -> {
                    full.append(token);
                    // 思考块过滤:无论服务端是否启用 reasoning-parser,客户只看到正式回答
                    String visible = thinkFilter.accept(token);
                    if (!visible.isEmpty()) {
                        sseService.sendToken(conversationId, visible);
                    }
                })
                .doFinally(signal -> identityContext.unbind(conversationId))
                .doOnComplete(() -> finish(conversationId, full.toString()))
                .doOnError(err -> {
                    log.error("LLM 流式调用失败 conversationId={}", conversationId, err);
                    finish(conversationId, "抱歉,系统开小差了,请稍后重试或联系人工客服。");
                })
                .subscribe();
    }

    private void finish(Long conversationId, String text) {
        // 终态兜底:剥离思考块 + 清理前导空白
        String cleaned = ThinkFilter.strip(text);
        if (cleaned.isEmpty()) {
            cleaned = "抱歉,我没有理解您的问题,请换个说法或联系人工客服。";
        }
        messageMapper.insert(Message.of(conversationId, "assistant", cleaned));
        sseService.sendComplete(conversationId, cleaned);
    }

    private String loadSystemPrompt() {
        try (var in = systemPromptResource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("system prompt 加载失败", e);
        }
    }
}
