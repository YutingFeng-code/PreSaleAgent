package com.presaleagent.memory;

import java.time.Instant;

public record CompactionRecord(String userId, String conversationId, String sourceHash,
                               int sourceMessages, int retainedMessages, boolean success,
                               boolean fallback, String trigger, Instant completedAt,
                               String error) {}
