package com.presaleagent.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ProductFact(String productId, String name, String brand, BigDecimal price, String currency,
                          Integer stock, Integer deliveryDays, Map<String, Object> specs, List<String> tags,
                          String url, Instant retrievedAt, Instant expiresAt) {
    public ProductFact {
        specs = specs == null ? Map.of() : Map.copyOf(specs);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
