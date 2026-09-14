package com.presaleagent.hook;

import java.util.Map;

public record HookDecision(HookAction action, String message, Map<String, Object> changes) {
    public HookDecision {
        action = action == null ? HookAction.ALLOW : action;
        message = message == null ? "" : message;
        changes = changes == null ? Map.of() : Map.copyOf(changes);
    }
    public static HookDecision allow() { return new HookDecision(HookAction.ALLOW, "", Map.of()); }
    public static HookDecision block(String message) { return new HookDecision(HookAction.BLOCK, message, Map.of()); }
}
