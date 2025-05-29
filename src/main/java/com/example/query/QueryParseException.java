package com.example.query;

/**
 * Exception thrown when there is an error parsing a query.
 */
public class QueryParseException extends Exception {
    private static final long serialVersionUID = 1L;

    public QueryParseException(String message) {
        super(message);
    }

    public QueryParseException(String message, Throwable cause) {
        super(message, cause);
    }
}