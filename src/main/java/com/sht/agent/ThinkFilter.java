package com.sht.agent;

/**
 * Qwen3 思考块流式过滤器。
 * 兼容两种 vLLM 部署:服务端带 --reasoning-parser(content 干净,直接透传)
 * 或不带(content 含 &lt;think&gt; 块,过滤器剥离)——保证客户永远只看到正式回答。
 */
public class ThinkFilter {

    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private enum State { DECIDING, IN_THINK, NORMAL }

    private State state = State.DECIDING;
    private final StringBuilder buffer = new StringBuilder();

    /** 输入一个流式 token,返回应当展示给客户的部分(可能为空) */
    public synchronized String accept(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }
        switch (state) {
            case NORMAL:
                return token;
            case DECIDING: {
                // 判定流是否以 <think> 开头(容忍前导空白与跨 token 拆分)
                buffer.append(token);
                String trimmed = buffer.toString().stripLeading();
                if (trimmed.isEmpty()) {
                    return ""; // 纯空白:可能是思考块前导,继续缓冲
                }
                if (trimmed.startsWith(OPEN)) {
                    buffer.setLength(0);
                    int idx = trimmed.indexOf(CLOSE);
                    if (idx >= 0) {
                        // 首段即含完整思考块:剥离后直接进入正文态
                        state = State.NORMAL;
                        return trimmed.substring(idx + CLOSE.length());
                    }
                    state = State.IN_THINK;
                    return "";
                }
                if (OPEN.startsWith(trimmed)) {
                    return ""; // 可能是 "<think>" 的前缀,继续缓冲
                }
                state = State.NORMAL;
                String out = buffer.toString();
                buffer.setLength(0);
                return out;
            }
            case IN_THINK: {
                // 思考内容全部丢弃,直到出现 "</think>"(无需保留尾巴——整块都不要)
                buffer.append(token);
                String s = buffer.toString();
                int idx = s.indexOf(CLOSE);
                if (idx < 0) {
                    return "";
                }
                String rest = s.substring(idx + CLOSE.length());
                buffer.setLength(0);
                state = State.NORMAL;
                return rest;
            }
            default:
                return token;
        }
    }

    /** 终态兜底:对完整文本剥离思考块(含未闭合的残缺块) */
    public static String strip(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<think>.*$", "")
                .strip();
    }
}
