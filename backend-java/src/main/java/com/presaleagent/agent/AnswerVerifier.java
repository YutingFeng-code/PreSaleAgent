package com.presaleagent.agent;

import com.presaleagent.llm.LlmGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class AnswerVerifier {

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public AnswerVerifier(LlmGateway llmGateway, ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    public VerificationResult verify(String question, String answer, String context) {
        FactCheck factCheck = deterministicFactCheck(question, answer, context);
        String prompt = """
                你是客服回答质量校验器。判断回答是否解决用户问题、是否基于上下文、是否需要转人工。
                用户问题: %s
                回答: %s
                上下文: %s
                返回 JSON: {"pass":true,"grounded":true,"need_escalation":false,"reason":"..."}
                """.formatted(question, answer, context == null ? "" : context);
        try {
            String raw = llmGateway.chat("", prompt, 0.0, 256);
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            Map<String, Object> data = objectMapper.readValue(raw.substring(start, end + 1), new TypeReference<>() {
            });
            boolean llmPass = Boolean.TRUE.equals(data.get("pass"));
            boolean grounded = Boolean.TRUE.equals(data.get("grounded")) && !factCheck.violation();
            return new VerificationResult(
                    llmPass && !factCheck.violation(),
                    grounded,
                    Boolean.TRUE.equals(data.get("need_escalation")),
                    String.valueOf(data.getOrDefault("reason", "")), factCheck.violation(), factCheck.evidenceIds(),
                    catalogStatus(context)
            );
        } catch (Exception ex) {
            boolean grounded = context != null && !context.isBlank();
            return new VerificationResult(!factCheck.violation(), grounded && !factCheck.violation(),
                    answer != null && answer.contains("转人工"), "verifier fallback", factCheck.violation(), factCheck.evidenceIds(), catalogStatus(context));
        }
    }

    private FactCheck deterministicFactCheck(String question, String answer, String context) {
        String q = question == null ? "" : question;
        if (!(q.contains("商品") || q.contains("产品") || q.contains("价格") || q.contains("库存") || q.contains("规格")
                || q.contains("推荐") || q.contains("优惠") || q.contains("现货") || q.contains("对比") || q.contains("到货"))) {
            return new FactCheck(false, List.of());
        }
        if (answer == null || answer.isBlank() || context == null || context.isBlank()) return new FactCheck(false, List.of());
        List<String> ids = new ArrayList<>();
        boolean violation = false;
        // Numbers presented as price/stock/delivery/spec claims must occur in retrieved evidence.
        for (String number : Pattern.compile("(?<![A-Za-z])\\d+(?:\\.\\d+)?").matcher(answer).results().map(m -> m.group()).distinct().toList()) {
            if (!context.contains(number)) { violation = true; ids.add("missing-number:" + number); }
        }
        return new FactCheck(violation, ids);
    }

    private String catalogStatus(String context) {
        if (context == null) return "UNKNOWN";
        for (String status : List.of("SUCCESS", "EMPTY", "TIMEOUT", "HTTP_ERROR", "INVALID_RESPONSE", "NOT_CONFIGURED", "CIRCUIT_OPEN")) {
            if (context.contains("\"status\":\"" + status + "\"")) return status;
        }
        return "UNKNOWN";
    }

    private record FactCheck(boolean violation, List<String> evidenceIds) {}

    public record VerificationResult(boolean pass, boolean grounded, boolean needEscalation, String reason,
                                     boolean factViolation, List<String> evidenceIds, String catalogStatus) {
        public VerificationResult(boolean pass, boolean grounded, boolean needEscalation, String reason) {
            this(pass, grounded, needEscalation, reason, false, List.of(), "UNKNOWN");
        }
    }
}
