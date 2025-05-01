package com.example.index;

import java.util.Objects;

/**
 * Configuration class for index generation settings.
 * Handles settings related to index preservation, safety checks, and performance tuning.
 */
public class IndexConfig {
    
    private final boolean preserveExistingIndex;
    private final long sizeThresholdForConfirmation;
    private final Integer limit;
    private final int batchSize;
    
    private IndexConfig(Builder builder) {
        this.preserveExistingIndex = builder.preserveExistingIndex;
        this.sizeThresholdForConfirmation = builder.sizeThresholdForConfirmation;
        this.limit = builder.limit;
        this.batchSize = builder.batchSize;
    }
    
    public boolean shouldPreserveExistingIndex() {
        return preserveExistingIndex;
    }
    
    public long getSizeThresholdForConfirmation() {
        return sizeThresholdForConfirmation;
    }

    public Integer getLimit() {
        return limit;
    }

    public int getBatchSize() {
        return batchSize;
    }
    
    public static class Builder {
        private boolean preserveExistingIndex = false;
        private long sizeThresholdForConfirmation = 1024 * 1024 * 1024; // 1GB
        private Integer limit = null;
        private int batchSize = 100000; // Default batch size
        
        public Builder withPreserveExistingIndex(boolean preserve) {
            this.preserveExistingIndex = preserve;
            return this;
        }
        
        public Builder withSizeThresholdForConfirmation(long threshold) {
            if (threshold <= 0) {
                throw new IllegalArgumentException("Size threshold must be positive");
            }
            this.sizeThresholdForConfirmation = threshold;
            return this;
        }

        public Builder withBatchSize(int batchSize) {
            if (batchSize <= 0) {
                throw new IllegalArgumentException("Batch size must be positive");
            }
            this.batchSize = batchSize;
            return this;
        }
        
        public IndexConfig build() {
            return new IndexConfig(this);
        }
    }
} 