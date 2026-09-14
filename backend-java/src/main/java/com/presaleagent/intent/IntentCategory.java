package com.presaleagent.intent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum IntentCategory {
    QUERY("query"),
    COMPLAINT("complaint"),
    REQUEST("request"),
    GREETING("greeting"),
    ESCALATION("escalation"),
    TECHNICAL("technical"),
    BILLING("billing"),
    ACCOUNT("account"),
    FEEDBACK("feedback"),
    ORDER_STATUS("order_status"),
    LOGISTICS("logistics"),
    REFUND("refund"),
    INVOICE("invoice"),
    PAYMENT_ISSUE("payment_issue"),
    ACCOUNT_SECURITY("account_security"),
    TECHNICAL_LOGIN("technical_login"),
    TECHNICAL_CRASH("technical_crash"),
    HUMAN_HANDOFF("human_handoff"),
    PRODUCT_COMPARE("product_compare"),
    PRODUCT_RECOMMEND("product_recommend"),
    SPEC_INQUIRY("spec_inquiry"),
    AVAILABILITY("availability"),
    PRICE_PROMOTION("price_promotion"),
    OTHER("other");

    private final String wireValue;

    IntentCategory(String wireValue) {
        this.wireValue = wireValue;
    }

    /** Stable API/storage value; do not expose enum.name() to clients. */
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /** Accept both the current wire value and legacy Java enum names. */
    @JsonCreator
    public static IntentCategory fromWireValue(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (IntentCategory category : values()) {
            if (category.wireValue.equals(normalized) || category.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return category;
            }
        }
        return OTHER;
    }

    /** Coarse business group used by the public response contract. */
    public String groupWireValue() {
        return switch (this) {
            case PRODUCT_COMPARE, PRODUCT_RECOMMEND, SPEC_INQUIRY, AVAILABILITY, PRICE_PROMOTION -> "presales";
            case ORDER_STATUS, LOGISTICS -> "query";
            case REFUND, INVOICE, PAYMENT_ISSUE -> "billing";
            case ACCOUNT_SECURITY -> "account";
            case TECHNICAL_LOGIN, TECHNICAL_CRASH -> "technical";
            case HUMAN_HANDOFF -> "escalation";
            default -> wireValue;
        };
    }
}
