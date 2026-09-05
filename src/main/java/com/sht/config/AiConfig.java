package com.sht.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * ChatClient 装配:注册 5 个 @Tool 工具(全部经 @Governed 治理切面)。
 * 模型/温度由 application.yml 下发。
 *
 * 关键坑:Spring Boot 3.4 的 RestClient/WebClient 默认用 JDK HttpClient,
 * 明文 HTTP 会带 "Upgrade: h2c" 尝试升级 HTTP/2,vLLM(uvicorn)不支持 → 400。
 * 因此 RestClient/WebClient 都强制 HTTP/1.1。
 */
@Configuration
public class AiConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder().requestFactory(
                new JdkClientHttpRequestFactory(http11Client()));
    }

    @Bean
    public org.springframework.web.reactive.function.client.WebClient.Builder webClientBuilder() {
        return org.springframework.web.reactive.function.client.WebClient.builder()
                .clientConnector(new JdkClientHttpConnector(http11Client()));
    }

    private HttpClient http11Client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @Bean
    public ChatClient chatClient(OpenAiChatModel chatModel,
                                 com.sht.tools.AfterSaleTools afterSaleTools,
                                 com.sht.tools.PolicyTools policyTools) {
        return ChatClient.builder(chatModel)
                .defaultToolCallbacks(ToolCallbacks.from(afterSaleTools, policyTools))
                .build();
    }
}
