package com.presaleagent.hook;

public interface PreSaleAgentHook {
    HookEventType eventType();
    HookDecision handle(HookContext context);
}
