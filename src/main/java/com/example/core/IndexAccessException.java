package com.example.core;

/**
 * Exception thrown by IndexAccess operations.
 * Provides detailed error information and wraps underlying exceptions.
 */
public class IndexAccessException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String indexType;
    private final ErrorType errorType;

    public enum ErrorType {
        INITIALIZATION_ERROR,
        READ_ERROR,
        WRITE_ERROR,
        RESOURCE_ERROR,
        CORRUPTION_ERROR
    }

    public IndexAccessException(String message, String indexType, ErrorType errorType) {
        super(message);
        this.indexType = indexType;
        this.errorType = errorType;
    }

    public IndexAccessException(String message, String indexType, ErrorType errorType, Throwable cause) {
        super(message, cause);
        this.indexType = indexType;
        this.errorType = errorType;
    }

    public String getIndexType() {
        return indexType;
    }

    public ErrorType getErrorType() {
        return errorType;
    }
}