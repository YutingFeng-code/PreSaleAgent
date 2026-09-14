package com.presaleagent.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum AgentType {
    GENERAL("general"),
    PRODUCT_ADVISOR("product_advisor"),
    TECHNICAL("technical"),
    BILLING("billing"),
    ESCALATION("escalation");

    private final String wireValue;

    AgentType(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static AgentType fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return GENERAL;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (AgentType type : values()) {
            if (type.wireValue.equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        return GENERAL;
    }
}
