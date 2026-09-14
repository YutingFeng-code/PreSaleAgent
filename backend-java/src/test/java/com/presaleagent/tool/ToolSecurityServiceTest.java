package com.presaleagent.tool;

import com.presaleagent.agent.AgentType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolSecurityServiceTest {
    private final ToolSecurityService security = new ToolSecurityService();

    @Test void productAdvisorCanOnlyUseBoundedCatalogQuery() {
        assertTrue(security.validate(AgentType.PRODUCT_ADVISOR, "search_product_catalog", "耳机", "", 5).allowed());
        assertFalse(security.validate(AgentType.BILLING, "search_product_catalog", "耳机", "", 5).allowed());
        assertFalse(security.validate(AgentType.PRODUCT_ADVISOR, "search_product_catalog", "", "", 5).allowed());
        assertFalse(security.validate(AgentType.PRODUCT_ADVISOR, "search_product_catalog", "耳机", "", 21).allowed());
    }

    @Test void rejectsPrivateNetworkCatalogHosts() {
        assertFalse(security.validateBaseUrl("http://127.0.0.1:8080").allowed());
        assertFalse(security.validateBaseUrl("http://localhost").allowed());
    }
}
