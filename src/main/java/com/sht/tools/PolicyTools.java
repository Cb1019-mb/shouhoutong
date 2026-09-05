package com.sht.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sht.governance.Governed;
import com.sht.rag.SearchPolicyService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 政策知识库检索工具(RAG,带引用返回) */
@Component
public class PolicyTools {

    private final SearchPolicyService searchPolicyService;
    private final ObjectMapper objectMapper;

    public PolicyTools(SearchPolicyService searchPolicyService, ObjectMapper objectMapper) {
        this.searchPolicyService = searchPolicyService;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "检索店铺售后政策知识库(退货政策/退款政策/运费规则等)。当买家询问政策类问题(怎么退货、多久退款、运费谁出等)时必须先调用此工具,基于返回条款回答并注明出处;查不到就转人工,禁止编造政策。")
    @Governed("searchPolicy")
    public String searchPolicy(@ToolParam(description = "政策问题,如'七天无理由退货条件'") String query,
                               org.springframework.ai.chat.model.ToolContext toolContext) {
        Map<String, Object> result = searchPolicyService.search(query);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"status\":\"NOT_FOUND\"}";
        }
    }
}
