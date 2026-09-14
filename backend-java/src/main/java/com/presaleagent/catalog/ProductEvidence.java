package com.presaleagent.catalog;

public record ProductEvidence(ProductFact fact, String evidenceId, String sourceType, double confidence) {}
