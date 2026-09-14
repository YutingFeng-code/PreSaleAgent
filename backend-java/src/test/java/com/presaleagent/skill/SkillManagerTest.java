package com.presaleagent.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.presaleagent.config.PreSaleAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManagerTest {

    private Path root;

    private SkillManager manager;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(Path.of("target", "skill-test-" + UUID.randomUUID()));
        PreSaleAgentProperties properties = new PreSaleAgentProperties();
        properties.getSkills().setRootDir(root.toString());
        manager = new SkillManager(properties, new ObjectMapper());
        write("product_compare", "product_compare", "COMPARE_ONLY", "比较,哪个");
        write("product_recommend", "product_recommend", "RECOMMEND_ONLY", "推荐,预算");
        write("spec_inquiry", "spec_inquiry", "SPEC_ONLY", "规格,支持");
        write("availability", "availability", "AVAILABILITY_ONLY", "库存,发货");
        write("price_promotion", "price_promotion", "PRICE_ONLY", "价格,优惠");
        write("legacy", "", "LEGACY_ONLY", "商品");
        manager.load();
    }

    @Test
    void selectsOnlySkillMappedToIntent() {
        String prompt = manager.promptFor("A 和 B 哪个好？", "product_advisor", "product_compare");

        assertTrue(prompt.contains("COMPARE_ONLY"));
        assertFalse(prompt.contains("RECOMMEND_ONLY"));
        assertFalse(prompt.contains("SPEC_ONLY"));
        assertFalse(prompt.contains("AVAILABILITY_ONLY"));
        assertFalse(prompt.contains("PRICE_ONLY"));
    }

    @Test
    void allPresalesIntentsResolveIndependently() {
        Map<String, String> expected = Map.of(
                "product_compare", "COMPARE_ONLY",
                "product_recommend", "RECOMMEND_ONLY",
                "spec_inquiry", "SPEC_ONLY",
                "availability", "AVAILABILITY_ONLY",
                "price_promotion", "PRICE_ONLY"
        );
        expected.forEach((intent, marker) -> assertTrue(
                manager.promptFor("任意消息", "product_advisor", intent).contains(marker), intent));
    }

    @Test
    void knownIntentDoesNotFallBackToLegacySkill() {
        String prompt = manager.promptFor("商品推荐", "product_advisor", "product_compare");

        assertTrue(prompt.contains("COMPARE_ONLY"));
        assertFalse(prompt.contains("LEGACY_ONLY"));
    }

    @Test
    void emptyIntentUsesLegacyKeywordFallback() {
        String prompt = manager.promptFor("商品咨询", "product_advisor");

        assertTrue(prompt.contains("LEGACY_ONLY"));
    }

    @Test
    void unknownIntentUsesLegacyKeywordFallback() {
        String prompt = manager.promptFor("商品咨询", "product_advisor", "other");

        assertTrue(prompt.contains("LEGACY_ONLY"));
    }

    @Test
    void summaryExposesIntentMapping() {
        @SuppressWarnings("unchecked")
        var skills = (java.util.List<Map<String, Object>>) manager.summary().get("skills");
        assertTrue(skills.stream().anyMatch(skill -> "product_compare".equals(skill.get("intent"))));
    }

    private void write(String directory, String intent, String marker, String keywords) throws Exception {
        Path dir = Files.createDirectories(root.resolve(directory));
        String content = "---\n"
                + "name: " + directory + "\n"
                + "agents: product_advisor\n"
                + (intent.isBlank() ? "" : "intent: " + intent + "\n")
                + "keywords: " + keywords + "\n"
                + "enabled: true\n"
                + "---\n\n"
                + marker + "\n";
        Files.writeString(dir.resolve("SKILL.md"), content);
    }
}
