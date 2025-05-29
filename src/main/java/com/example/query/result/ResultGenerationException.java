package com.example.query.result;

/**
 * Exception thrown when an error occurs during result generation.
 */
public class ResultGenerationException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String component;
    private final ErrorType errorType;

    /**
     * Enum defining the types of errors that can occur during result generation.
     */
    public enum ErrorType {
        METADATA_ACCESS_ERROR,
        SNIPPET_GENERATION_ERROR,
        VARIABLE_BINDING_ERROR,
        FORMATTING_ERROR,
        INTERNAL_ERROR
    }

    /**
     * Creates a new ResultGenerationException with a message, component, and error type.
     *
     * @param message The error message
     * @param component The component that caused the error
     * @param errorType The type of error
     */
    public ResultGenerationException(String message, String component, ErrorType errorType) {
        super(message);
        this.component = component;
        this.errorType = errorType;
    }

    /**
     * Creates a new ResultGenerationException with a message, cause, component, and error type.
     *
     * @param message The error message
     * @param cause The cause of the error
     * @param component The component that caused the error
     * @param errorType The type of error
     */
    public ResultGenerationException(String message, Throwable cause, String component, ErrorType errorType) {
        super(message, cause);
        this.component = component;
        this.errorType = errorType;
    }

    /**
     * Gets the component that caused the error.
     *
     * @return The component
     */
    public String getComponent() {
        return component;
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