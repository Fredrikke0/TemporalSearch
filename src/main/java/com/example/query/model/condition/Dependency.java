package com.example.query.model.condition;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a dependency condition in the query language.
 * This condition matches documents based on syntactic dependencies between words.
 */
public record Dependency(
    String governor,
    String relation,
    String dependent,
    String qualifiedVariableName,
    boolean isVariable
) implements Condition {
    
    private static final Logger logger = LoggerFactory.getLogger(Dependency.class);
    
    /**
     * Creates a new dependency condition with validation.
     */
    public Dependency {
        Objects.requireNonNull(governor, "governor cannot be null");
        Objects.requireNonNull(relation, "relation cannot be null");
        Objects.requireNonNull(dependent, "dependent cannot be null");
        
        if (isVariable) {
            Objects.requireNonNull(qualifiedVariableName, "qualifiedVariableName cannot be null when isVariable is true");
        }
    }

    /**
     * Creates a new dependency condition without variable binding.
     */
    public Dependency(String governor, String relation, String dependent) {
        this(governor, relation, dependent, null, false);
    }

    /**
     * Creates a new dependency condition with variable binding.
     * 
     * @param governor The governor term
     * @param relation The dependency relation
     * @param dependent The dependent term
     * @param variableName The variable name to bind the dependency to
     */
    public Dependency(String governor, String relation, String dependent, String variableName) {
        this(governor, relation, dependent, variableName, true);
    }

    /**
     * Returns whether this condition uses variable binding.
     */
    public boolean isVariable() {
        return isVariable;
    }

    /**
     * Returns the variable name if this is a variable binding condition.
     */
    public String getVariableName() {
        return qualifiedVariableName;
    }
    
    /**
     * Returns the variable name if this is a variable binding condition.
     * 
     * @return The qualified variable name, or null if not bound
     */
    public String variableName() {
        return qualifiedVariableName;
    }
    
    /**
     * Determines if a string is a variable reference.
     */
    private boolean isVariableReference(String s) {
        return s != null && s.startsWith("?");
    }
    
    @Override
    public String getType() {
        return "DEPENDENCY";
    }
    
    @Override
    public Set<String> getProducedVariables() {
        return isVariable ? Set.of(qualifiedVariableName) : Collections.emptySet();
    }
    
    @Override
    public Set<String> getConsumedVariables() {
        Set<String> consumed = new HashSet<>();
        if (isVariableReference(governor)) {
            consumed.add(governor);
            logger.debug("Marking {} as consumed variable (governor)", governor);
        }
        if (isVariableReference(dependent)) {
            consumed.add(dependent);
            logger.debug("Marking {} as consumed variable (dependent)", dependent);
        }
        logger.debug("Reporting consumed variables: {}", consumed);
        return consumed;
    }
    
    @Override
    public VariableType getProducedVariableType() {
        return VariableType.DEPENDENCY;
    }
    
    @Override
    public void registerVariables(VariableRegistry registry) {
        logger.debug("Registering variables for DEPENDS({}, {}, {})", governor, relation, dependent);
        
        // Produced and Consumed variables are now registered directly in QueryModelBuilder
        // using qualified names for registry lookup.
        
        /*
        if (isVariable) {
            logger.debug("Registering {} as producer variable", variableName);
            registry.registerProducer(variableName, getProducedVariableType(), getType());
        }
        
        // Register consumed variables
        if (isVariableReference(governor)) {
            logger.debug("Registering {} as consumer variable (governor)", governor);
            registry.registerConsumer(governor, VariableType.ANY, getType());
        } else {
            logger.debug("Governor '{}' is not a variable reference", governor);
        }
        
        if (isVariableReference(dependent)) {
            logger.debug("Registering {} as consumer variable (dependent)", dependent);
            registry.registerConsumer(dependent, VariableType.ANY, getType());
        } else {
            logger.debug("Dependent '{}' is not a variable reference", dependent);
        }
        */
    }
    
    @Override
    public String toString() {
        if (isVariable) {
            return String.format("DEPENDS(%s, %s, %s) BIND %s", governor, relation, dependent, qualifiedVariableName);
        } else {
            return String.format("DEPENDS(%s, %s, %s)", governor, relation, dependent);
        }
    }
} 