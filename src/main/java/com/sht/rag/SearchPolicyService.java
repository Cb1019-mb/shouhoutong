package com.sht.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 政策检索:查询侧加 BGE 检索指令前缀,低于阈值的查询返回 NOT_FOUND(拒答),
 * 上层工具据固定话术转人工 —— 绝不让 LLM 编造政策。
 */
@Slf4j
@Service
public class SearchPolicyService {

    private final VectorStore vectorStore;
    private final String queryPrefix;
    private final double similarityThreshold;

    public SearchPolicyService(VectorStore vectorStore,
                               @Value("${rag.query-prefix:}") String queryPrefix,
                               @Value("${rag.similarity-threshold:0.45}") double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.queryPrefix = queryPrefix;
        this.similarityThreshold = similarityThreshold;
    }

    public Map<String, Object> search(String query) {
        String embedded = (queryPrefix == null ? "" : queryPrefix) + query;
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(embedded).topK(3).similarityThreshold(similarityThreshold).build());
        Map<String, Object> out = new LinkedHashMap<>();
        if (hits == null || hits.isEmpty()) {
            out.put("status", "NOT_FOUND");
            out.put("reply_guidance", "政策库中没有找到依据,请使用固定话术建议买家联系人工客服,禁止自行编造政策。");
            return out;
        }
        List<Map<String, Object>> refs = new ArrayList<>();
        for (Document doc : hits) {
            Map<String, Object> meta = doc.getMetadata();
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("title", String.valueOf(meta.getOrDefault("title", "")));
            ref.put("source", String.valueOf(meta.getOrDefault("source", "")));
            ref.put("text", doc.getText());
            ref.put("score", doc.getScore());
            refs.add(ref);
        }
        out.put("status", "OK");
        out.put("hits", refs);
        out.put("reply_guidance", "回答必须基于返回的政策条款,并注明'依据《xx政策》'及条款标题;不得超出条款内容做承诺。");
        return out;
    }
}
