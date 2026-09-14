package com.presaleagent.catalog;

import com.presaleagent.config.PreSaleAgentProperties;
import com.presaleagent.tool.ToolSecurityService;
import com.presaleagent.tool.CircuitBreaker;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.List;
import java.util.Map;

@Service
public class ProductCatalogService {
    private final RestClient client;
    private final String baseUrl;
    private final String token;
    private final int cacheSeconds;
    private final ToolSecurityService security;
    private final Semaphore permits = new Semaphore(8);
    private final CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofSeconds(30));
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ProductCatalogService() {
        this(new PreSaleAgentProperties(), new ToolSecurityService());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProductCatalogService(PreSaleAgentProperties properties, ToolSecurityService security) {
        int timeoutSeconds = parseTimeout(env("PRESALEAGENT_CATALOG_TIMEOUT_SECONDS", "ECHOMIND_CATALOG_TIMEOUT_SECONDS"));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(timeoutSeconds));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder().requestFactory(factory).build();
        this.baseUrl = env("PRESALEAGENT_CATALOG_API_URL", "ECHOMIND_CATALOG_API_URL").replaceAll("/+$", "");
        this.token = env("PRESALEAGENT_CATALOG_API_TOKEN", "ECHOMIND_CATALOG_API_TOKEN");
        this.cacheSeconds = properties.getCatalog().getCacheSeconds();
        this.security = security;
    }

    public Map<String, Object> search(String query, String category, int limit) {
        if (query == null || query.isBlank() || query.codePointCount(0, query.length()) > 200) return failure(CatalogStatus.INVALID_RESPONSE, "query 必须为 1-200 个字符");
        limit = Math.max(1, Math.min(limit, 20));
        if (baseUrl.isBlank()) return failure(CatalogStatus.NOT_CONFIGURED, "商品目录 API 未配置");
        if (!breaker.allow()) return failure(CatalogStatus.CIRCUIT_OPEN, "商品目录熔断中，请稍后重试");
        ToolSecurityService.Validation urlCheck = security.validateBaseUrl(baseUrl);
        if (!urlCheck.allowed()) return failure(CatalogStatus.HTTP_ERROR, urlCheck.reason());
        String key = query + "|" + (category == null ? "" : category) + "|" + limit;
        Cached cached = cache.get(key);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) return cached.payload();
        if (!permits.tryAcquire()) return failure(CatalogStatus.CIRCUIT_OPEN, "商品目录并发请求已达上限");
        try {
            var request = client.get().uri(baseUrl + "/products/search?query={query}&category={category}&limit={limit}", query, category == null ? "" : category, limit);
            if (!token.isBlank()) request.header("Authorization", "Bearer " + token);
            Map<?, ?> payload = request.retrieve().body(Map.class);
            Object items = payload == null ? null : payload.get("items");
            if (!(items instanceof List<?>)) return failure(CatalogStatus.INVALID_RESPONSE, "商品目录响应 items 格式无效");
            CatalogStatus status = ((List<?>) items).isEmpty() ? CatalogStatus.EMPTY : CatalogStatus.SUCCESS;
            Map<String, Object> result = Map.of("success", status == CatalogStatus.SUCCESS, "available", true, "status", status.name(), "results", normalize((List<?>) items));
            cache.put(key, new Cached(result, Instant.now().plusSeconds(cacheSeconds)));
            breaker.recordSuccess();
            return result;
        } catch (Exception ex) {
            CatalogStatus status = ex.getClass().getSimpleName().toLowerCase().contains("timeout") ? CatalogStatus.TIMEOUT : CatalogStatus.HTTP_ERROR;
            breaker.recordFailure();
            return failure(status, "商品目录不可用");
        } finally {
            permits.release();
        }
    }

    public List<ProductEvidence> searchEvidence(String query, String category, int limit) {
        Map<String, Object> result = search(query, category, limit);
        CatalogStatus status = CatalogStatus.valueOf(String.valueOf(result.getOrDefault("status", CatalogStatus.HTTP_ERROR.name())));
        if (status != CatalogStatus.SUCCESS) return List.of();
        List<ProductEvidence> evidence = new java.util.ArrayList<>();
        Object raw = result.get("results");
        if (raw instanceof List<?> items) for (Object item : items) if (item instanceof Map<?, ?> map) {
            try {
                ProductFact fact = new ProductFact(String.valueOf(value(map, "id", "")), String.valueOf(value(map, "name", "")),
                        String.valueOf(value(map, "brand", "")), decimal(map.get("price")), String.valueOf(value(map, "currency", "CNY")),
                        integer(map.get("stock")), integer(map.get("delivery_days")), map.get("specs") instanceof Map<?, ?> m ? (Map<String,Object>) (Map<?,?>) m : Map.of(),
                        map.get("tags") instanceof List<?> tags ? tags.stream().map(String::valueOf).toList() : List.of(), String.valueOf(value(map, "url", "")),
                        Instant.now(), Instant.now().plusSeconds(Math.max(1, cacheSeconds)));
                evidence.add(new ProductEvidence(fact, "catalog:" + fact.productId(), "catalog_api", 1.0));
            } catch (Exception ignored) { }
        }
        return List.copyOf(evidence);
    }

    private String env(String primary, String legacy) {
        String value = System.getenv(primary);
        if (value == null || value.isBlank()) {
            value = System.getenv(legacy);
        }
        return value == null ? "" : value.trim();
    }

    private int parseTimeout(String value) {
        try {
            return Math.max(1, Math.min(Integer.parseInt(value), 30));
        } catch (NumberFormatException ex) {
            return 3;
        }
    }

    private Map<String, Object> failure(CatalogStatus status, String error) {
        return Map.of("success", false, "available", false, "status", status.name(), "results", List.of(), "error", error);
    }

    private List<Map<String, Object>> normalize(List<?> items) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Object item : items) if (item instanceof Map<?, ?> map) {
            Map<String,Object> value = new java.util.LinkedHashMap<>();
            for (String key : List.of("id","name","brand","price","currency","stock","delivery_days","specs","tags","url")) if (map.containsKey(key)) value.put(key, map.get(key));
            out.add(value);
        }
        return List.copyOf(out);
    }
    private Object value(Map<?, ?> map, String key, Object fallback) { return map.containsKey(key) && map.get(key) != null ? map.get(key) : fallback; }
    private Integer integer(Object value) { return value instanceof Number n ? n.intValue() : value == null ? null : Integer.valueOf(String.valueOf(value)); }
    private BigDecimal decimal(Object value) { return value == null ? null : new BigDecimal(String.valueOf(value)); }
    private record Cached(Map<String,Object> payload, Instant expiresAt) {}
}
