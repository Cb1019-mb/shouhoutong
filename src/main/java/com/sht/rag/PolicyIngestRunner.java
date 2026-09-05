package com.sht.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sht.audit.AuditService;
import com.sht.audit.entity.PolicyHitStat;
import com.sht.audit.mapper.PolicyHitStatMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 政策文档入库:resources/policies/*.md 按 "## " 条款切块,
 * 经 Infinity bge-small-zh-v1.5(512 维)嵌入后写入 Qdrant policy 集合。
 * 用 policy_hit_stat 做灌库标记,只灌一次(文档变更时删标记重启即可重灌)。
 */
@Slf4j
@Component
public class PolicyIngestRunner implements ApplicationRunner {

    private static final String INGEST_FLAG = "policy_ingest_done";

    private final VectorStore vectorStore;
    private final PolicyHitStatMapper statMapper;
    private final AuditService auditService;

    public PolicyIngestRunner(VectorStore vectorStore, PolicyHitStatMapper statMapper,
                              AuditService auditService) {
        this.vectorStore = vectorStore;
        this.statMapper = statMapper;
        this.auditService = auditService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long done = statMapper.selectCount(new LambdaQueryWrapper<PolicyHitStat>()
                .eq(PolicyHitStat::getRuleKey, INGEST_FLAG));
        if (done != null && done > 0) {
            log.info("政策向量库已灌入,跳过");
            return;
        }
        try {
            int chunks = ingest();
            PolicyHitStat flag = new PolicyHitStat();
            flag.setRuleKey(INGEST_FLAG);
            flag.setHitCount(chunks);
            flag.setDate(java.time.LocalDate.now());
            statMapper.insert(flag);
            log.info("政策文档入库完成:{} 个条款块", chunks);
        } catch (Exception e) {
            // Infinity 未就绪等场景不阻塞应用启动,下次重启重试
            log.warn("政策文档入库失败(不阻塞启动,重启重试):{}", e.getMessage());
        }
    }

    private int ingest() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath:policies/*.md");
        List<Document> documents = new ArrayList<>();
        for (var resource : resources) {
            String filename = resource.getFilename();
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String section : splitByHeading(content)) {
                int titleEnd = section.indexOf('\n');
                String title = (titleEnd > 0 ? section.substring(0, titleEnd) : section).replace("#", "").trim();
                String text = titleEnd > 0 ? section.substring(titleEnd).trim() : section;
                if (text.isBlank()) {
                    continue;
                }
                documents.add(new Document(text,
                        Map.of("source", filename, "title", title)));
            }
        }
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }
        return documents.size();
    }

    /** 按 "## " 标题切块 */
    static List<String> splitByHeading(String markdown) {
        List<String> sections = new ArrayList<>();
        String[] lines = markdown.split("\n");
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("## ")) {
                if (!current.isEmpty()) {
                    sections.add(current.toString().trim());
                }
                current = new StringBuilder(line);
            } else if (!current.isEmpty()) {
                current.append('\n').append(line);
            }
        }
        if (!current.isEmpty()) {
            sections.add(current.toString().trim());
        }
        return sections;
    }
}
