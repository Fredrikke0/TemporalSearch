package com.example.query.executor;

/**
 * Exception thrown when a query execution fails.
 * Provides detailed information about the cause of the failure.
 */
public class QueryExecutionException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String queryPart;
    private final ErrorType errorType;

    /**
     * Enum defining the types of errors that can occur during query execution.
     */
    public enum ErrorType {
        INDEX_ACCESS_ERROR,
        INVALID_CONDITION,
        UNSUPPORTED_OPERATION,
        INTERNAL_ERROR,
        MISSING_INDEX,
        MISSING_INDEX_DATA
    }

    /**
     * Creates a new QueryExecutionException with a message, query part, and error type.
     *
     * @param message The error message
     * @param queryPart The part of the query that caused the error
     * @param errorType The type of error
     */
    public QueryExecutionException(String message, String queryPart, ErrorType errorType) {
        super(message);
        this.queryPart = queryPart;
        this.errorType = errorType;
    }

    /**
     * Creates a new QueryExecutionException with a message, cause, query part, and error type.
     *
     * @param message The error message
     * @param cause The cause of the error
     * @param queryPart The part of the query that caused the error
     * @param errorType The type of error
     */
    public QueryExecutionException(String message, Throwable cause, String queryPart, ErrorType errorType) {
        super(message, cause);
        this.queryPart = queryPart;
        this.errorType = errorType;
    }

    /**
     * Gets the part of the query that caused the error.
     *
     * @return The query part
     */
    public String getQueryPart() {
        return queryPart;
    }

    /**
     * Gets the type of error.
     *
     * @return The error type
     */
    public ErrorType getErrorType() {
        return errorType;
    }
}