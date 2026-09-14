package com.presaleagent.tool;

import com.presaleagent.agent.AgentType;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ToolSecurityService {
    private static final Set<String> CATALOG_ALLOWED = Set.of("search_product_catalog");
    private final Map<String, ToolDefinition> definitions = Map.of(
            "search_knowledge_base", new ToolDefinition("search_knowledge_base", Set.of(AgentType.PRODUCT_ADVISOR, AgentType.GENERAL), Map.of(), java.time.Duration.ofSeconds(3), false),
            "search_product_catalog", new ToolDefinition("search_product_catalog", Set.of(AgentType.PRODUCT_ADVISOR), Map.of("limit", "1..20", "query", "1..200"), java.time.Duration.ofSeconds(3), false),
            "inspect_request_context", new ToolDefinition("inspect_request_context", Set.of(AgentType.PRODUCT_ADVISOR), Map.of(), java.time.Duration.ofSeconds(1), false)
    );

    public ToolDefinition definition(String name) { return definitions.get(name); }

    public Validation validate(AgentType agent, String toolName, String query, String category, int limit) {
        ToolDefinition definition = definitions.get(toolName);
        if (definition == null || !definition.allowedAgents().contains(agent)) return Validation.reject("工具未授权");
        if (!"search_product_catalog".equals(toolName)) return Validation.accept();
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 200) return Validation.reject("query 必须为 1-200 个字符");
        if (category != null && category.codePointCount(0, category.length()) > 80) return Validation.reject("category 过长");
        if (limit < 1 || limit > 20) return Validation.reject("limit 必须在 1-20 之间");
        return Validation.accept();
    }

    public Validation validateBaseUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return Validation.reject("目录 URL 协议不允许");
            String host = uri.getHost();
            if (host == null || host.isBlank() || host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("0.0.0.0") || host.toLowerCase(Locale.ROOT).endsWith(".internal")) return Validation.reject("目录 URL 主机不允许");
            InetAddress address = InetAddress.getByName(host);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) return Validation.reject("禁止访问内网地址");
            return Validation.accept();
        } catch (Exception ex) { return Validation.reject("目录 URL 无效"); }
    }

    public record Validation(boolean allowed, String reason) {
        static Validation accept() { return new Validation(true, ""); }
        static Validation reject(String reason) { return new Validation(false, reason); }
    }
}
