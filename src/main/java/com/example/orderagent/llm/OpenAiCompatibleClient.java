package com.example.orderagent.llm;

import com.example.orderagent.agent.core.AgentMemory.LlmMessage;
import com.example.orderagent.agent.core.AgentMemory.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的客户端：DeepSeek、通义（兼容模式）、各类中转网关都能直接用。
 *
 * <p>用 JDK 自带的 HttpClient，不引重依赖。注意两点：
 * <ul>
 *   <li>连接超时和读超时分开配置，模型"首 token 慢"和"连接不上"是两类故障；</li>
 *   <li>HTTP 非 200 时抛出 {@link LlmException}，由上层决定重试/降级，不要在这里吞掉。</li>
 * </ul>
 */
public class OpenAiCompatibleClient implements LlmClient {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final LlmProperties props;

    public OpenAiCompatibleClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
    }

    @Override
    public String modelName() {
        return props.getModel();
    }

    @Override
    public LlmResponse chat(List<LlmMessage> messages, List<Map<String, Object>> tools, String toolChoice) {
        long start = System.currentTimeMillis();
        try {
            String body = buildBody(messages, tools, toolChoice);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(trimSlash(props.getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                int code = resp.statusCode();
                boolean retryable = code == 429 || code >= 500;
                long retryAfter = parseRetryAfter(resp);
                throw new LlmException("LLM HTTP " + code + ": " + clip(resp.body(), 300),
                        code, retryable, retryAfter, null);
            }
            return parse(resp.body(), System.currentTimeMillis() - start);
        } catch (LlmException e) {
            throw e;
        } catch (IOException e) {
            throw new LlmException("LLM IO error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM interrupted", e);
        }
    }

    private String buildBody(List<LlmMessage> messages, List<Map<String, Object>> tools, String toolChoice)
            throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", props.getModel());
        root.put("temperature", props.getTemperature());
        root.put("stream", false);

        ArrayNode msgs = root.putArray("messages");
        for (LlmMessage m : messages) {
            ObjectNode n = msgs.addObject();
            n.put("role", m.role());
            if (m.content() != null) {
                n.put("content", m.content());
            }
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                ArrayNode calls = n.putArray("tool_calls");
                for (ToolCall c : m.toolCalls()) {
                    ObjectNode cn = calls.addObject();
                    cn.put("id", c.id());
                    cn.put("type", "function");
                    ObjectNode fn = cn.putObject("function");
                    fn.put("name", c.functionName());
                    fn.put("arguments", mapper.writeValueAsString(c.arguments()));
                }
            }
            if (m.toolCallId() != null) {
                n.put("tool_call_id", m.toolCallId());
            }
            if (m.name() != null) {
                n.put("name", m.name());
            }
        }

        if (tools != null && !tools.isEmpty()) {
            root.putPOJO("tools", tools);
            root.put("tool_choice", toolChoice == null ? "auto" : toolChoice);
        }
        return mapper.writeValueAsString(root);
    }

    private LlmResponse parse(String body, long latency) throws IOException {
        JsonNode root = mapper.readTree(body);
        JsonNode choice = root.path("choices").path(0);
        JsonNode msg = choice.path("message");

        String content = msg.path("content").isMissingNode() || msg.path("content").isNull()
                ? null : msg.path("content").asText();
        String finish = choice.path("finish_reason").asText("");

        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode tc : msg.path("tool_calls")) {
            String id = tc.path("id").asText();
            String fn = tc.path("function").path("name").asText();
            String rawArgs = tc.path("function").path("arguments").asText("{}");
            Map<String, Object> args;
            try {
                args = mapper.readValue(rawArgs, Map.class);
            } catch (Exception e) {
                // 模型偶尔会返回非法 JSON：返回空参数，让本地校验层报 ARG_PARSE_ERROR
                args = Map.of("__parse_error", rawArgs);
            }
            calls.add(new ToolCall(id, fn, args));
        }

        long promptTokens = root.path("usage").path("prompt_tokens").asLong(0);
        long completionTokens = root.path("usage").path("completion_tokens").asLong(0);

        return new LlmResponse(content, calls, promptTokens, completionTokens, finish, latency);
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** 解析 Retry-After（秒）或 x-ratelimit-reset 头；拿不到返回 -1。 */
    private static long parseRetryAfter(HttpResponse<?> resp) {
        var h = resp.headers();
        String ra = h.firstValue("Retry-After").orElse(null);
        if (ra != null) {
            try {
                return Math.max(0, Long.parseLong(ra.trim()) * 1000);
            } catch (NumberFormatException ignored) {
                // 可能是 HTTP-date 格式，忽略
            }
        }
        return -1;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}