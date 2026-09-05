package com.sht.dashboard;

import com.sht.dashboard.mapper.StatsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsMapper statsMapper;

    @GetMapping("/today")
    public Map<String, Object> today() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversations", statsMapper.todayConversations());
        out.put("denies", statsMapper.todayDenies());
        out.put("refundAmount", statsMapper.todayRefundAmount());
        out.put("approvedCount", statsMapper.todayApprovedCount());
        out.put("avgApprovalSeconds", statsMapper.avgApprovalSeconds());
        out.put("ruleHits", statsMapper.todayRuleHits());
        out.put("weekConversations", statsMapper.weekConversations());
        return out;
    }
}
