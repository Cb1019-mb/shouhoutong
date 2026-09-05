package com.sht.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** user / assistant / tool */
    private String role;

    private String content;

    /** W2 起记录工具调用明细,纯聊天为 null */
    private String toolCallsJson;

    private LocalDateTime createdAt;

    public static Message of(Long conversationId, String role, String content) {
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(LocalDateTime.now());
        return m;
    }
}
