package com.presaleagent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentCategoryWireValueTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void usesStableSnakeCaseWireValues() throws Exception {
        assertEquals("\"product_recommend\"", objectMapper.writeValueAsString(IntentCategory.PRODUCT_RECOMMEND));
        assertEquals(IntentCategory.PRODUCT_RECOMMEND,
                objectMapper.readValue("\"product_recommend\"", IntentCategory.class));
        assertEquals(IntentCategory.PRODUCT_RECOMMEND,
                objectMapper.readValue("\"PRODUCT_RECOMMEND\"", IntentCategory.class));
    }

    @Test
    void separatesPresalesFromGenericQueryGroup() {
        assertEquals("presales", IntentCategory.PRODUCT_COMPARE.groupWireValue());
        assertEquals("presales", IntentCategory.PRICE_PROMOTION.groupWireValue());
        assertEquals("query", IntentCategory.ORDER_STATUS.groupWireValue());
        assertEquals("billing", IntentCategory.REFUND.groupWireValue());
    }
}
