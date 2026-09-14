package com.presaleagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfileMergeTest {

    @Test
    void keepsHistoricalPreferencesAndMergesNewFacts() {
        Map<String, Object> oldProfile = Map.of(
                "preferences", List.of("主动降噪"),
                "purchase_history", List.of("PA-100"),
                "budget", List.of("500 元"),
                "constraints", List.of("黑色")
        );
        Map<String, Object> delta = Map.of(
                "preferences", List.of("轻量", "主动降噪"),
                "purchase_history", List.of(),
                "budget", List.of("1000 元"),
                "constraints", List.of("白色"),
                "scenarios", List.of("办公室")
        );

        Map<String, Object> merged = MemoryManager.mergeProfile(oldProfile, delta);

        assertEquals(List.of("主动降噪", "轻量"), merged.get("preferences"));
        assertEquals(List.of("PA-100"), merged.get("purchase_history"));
        assertEquals(List.of("1000 元"), merged.get("budget"));
        assertEquals(List.of("白色"), merged.get("constraints"));
        assertEquals(List.of("办公室"), merged.get("scenarios"));
    }

    @Test
    void emptyCurrentValuesDoNotErasePreviousConstraints() {
        Map<String, Object> merged = MemoryManager.mergeProfile(
                Map.of("budget", List.of("500 元"), "recipients", List.of("女朋友")),
                Map.of("budget", List.of(), "recipients", List.of())
        );

        assertEquals(List.of("500 元"), merged.get("budget"));
        assertEquals(List.of("女朋友"), merged.get("recipients"));
    }
}
