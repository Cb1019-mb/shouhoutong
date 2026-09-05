package com.sht.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sht.chat.entity.Conversation;
import com.sht.chat.entity.Customer;
import com.sht.chat.mapper.ConversationMapper;
import com.sht.chat.mapper.CustomerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class PageController {

    private final CustomerMapper customerMapper;
    private final ConversationMapper conversationMapper;

    @GetMapping("/")
    public String index() {
        return "redirect:/simulator";
    }

    /** 客户模拟器页:选客户 → 发消息,扮演买家 */
    @GetMapping("/simulator")
    public String simulator(Model model) {
        model.addAttribute("customers", customerMapper.selectList(null));
        return "simulator";
    }

    /** 店主工作台(W1 先做会话列表 + 聊天回放;流式接入模拟器同款 SSE) */
    @GetMapping("/workbench")
    public String workbench(Model model) {
        List<Conversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>().orderByDesc(Conversation::getId));
        model.addAttribute("conversations", conversations);
        return "workbench";
    }

    /** 数据看板 */
    @GetMapping("/dashboard")
    public String dashboard() {
        return "dashboard";
    }

    /** 设置中心 */
    @GetMapping("/settings")
    public String settings() {
        return "settings";
    }

    @GetMapping("/workbench/{conversationId}")
    public String workbenchChat(@PathVariable Long conversationId, Model model) {
        Conversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            return "redirect:/workbench";
        }
        Customer customer = customerMapper.selectById(conv.getCustomerId());
        model.addAttribute("conversation", conv);
        model.addAttribute("customer", customer);
        model.addAttribute("conversations", conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>().orderByDesc(Conversation::getId)));
        return "workbench";
    }
}
