package com.example.query.model.condition;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;

/**
 * Represents a CONTAINS condition in the query language.
 * This condition checks if a document contains specific text or multiple terms.
 */
public record Contains(
    List<String> terms,
    String qualifiedVariableName,
    boolean isVariable
) implements Condition {
    
    /**
     * Creates a condition with validation.
     */
    public Contains {
        Objects.requireNonNull(terms, "Terms cannot be null");
        // Validation for 'value' removed
        
        // Make defensive copy of terms
        terms = List.copyOf(terms);
        
        if (isVariable) {
            Objects.requireNonNull(qualifiedVariableName, "qualifiedVariableName cannot be null when isVariable is true");
        }
    }

    /**
     * Creates a condition with a single term.
     * 
     * @param term The search term
     */
    public Contains(String term) {
        this(Collections.singletonList(Objects.requireNonNull(term, "term cannot be null")), null, false);
    }
    
    /**
     * Creates a condition with multiple terms.
     * 
     * @param terms List of search terms
     */
    public Contains(List<String> terms) {
        // Call the primary constructor, removing the 'value' argument
        this(terms, null, false);
    }

    /**
     * Creates a condition with a variable binding and a term.
     * 
     * @param term The search term
     * @param variableName The variable name to bind results to
     * @param isVariable Whether this condition binds to a variable
     */
    public Contains(String term, String variableName, boolean isVariable) {
        // Call the primary constructor, removing the 'value' argument
        // Assumes variableName passed here is already qualified by the builder
        this(Collections.singletonList(Objects.requireNonNull(term, "term cannot be null")), 
             variableName, isVariable);
    }

    /**
     * Returns the search terms.
     * 
     * @return Unmodifiable list of search terms
     */
    @Override
    public List<String> terms() {
        return terms; // Already unmodifiable from constructor
    }

    /**
     * Returns whether this condition uses variable binding.
     * 
     * @return true if this condition binds to a variable, false otherwise
     */
    public boolean isVariable() {
        return isVariable;
    }

    /**
     * Returns the variable name if this is a variable binding condition.
     * 
     * @return The variable name, or null if this is not a variable binding condition
     */
    public String variableName() {
        // Returns the qualified variable name if present, otherwise null
        return qualifiedVariableName;
    }

    @Override
    public String getType() {
        return "CONTAINS";
    }
    
    @Override
    public Set<String> getProducedVariables() {
        // Return the qualified name if bound
        return isVariable ? Set.of(qualifiedVariableName) : Collections.emptySet();
    }
    
    @Override
    public VariableType getProducedVariableType() {
        return VariableType.TEXT_SPAN;
    }
    
    @Override
    public void registerVariables(VariableRegistry registry) {
        if (isVariable) {
            registry.registerProducer(qualifiedVariableName, getProducedVariableType(), getType());
        }
    }

    @Override
    public String toString() {
        String termsString = String.join(" ", terms);
        if (isVariable) {
            // Format: CONTAINS("term") BIND alias.var
            return String.format("CONTAINS(\"%s\") BIND %s", termsString, qualifiedVariableName);
        } else {
            return String.format("CONTAINS(\"%s\")", termsString);
        }
    }
} 