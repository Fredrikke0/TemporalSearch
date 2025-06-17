package com.example.query.binding;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry for all variables in a query.
 * Tracks both producer and consumer variables using plain names.
 * Tracks both producer and consumer variables using **qualified** names (e.g., $main.var, alias.var).
 */
public class VariableRegistry {
    private static final Logger logger = LoggerFactory.getLogger(VariableRegistry.class);

    // Map of variable name (qualified) to producers
    private final Map<String, Set<ProducerVariable>> producers = new ConcurrentHashMap<>();

    // Map of variable name (qualified) to consumers
    private final Map<String, Set<ConsumerVariable>> consumers = new ConcurrentHashMap<>();

    /**
     * Registers a producer variable.
     *
     * @param qualifiedName The qualified variable name (e.g., $main.var, alias.var)
     * @param type The variable type
     * @param conditionType The condition type that produces the variable
     * @return The registered producer variable
     */
    public ProducerVariable registerProducer(String qualifiedName, VariableType type, String conditionType) {
        ProducerVariable var = new ProducerVariable(qualifiedName, type, conditionType);

        producers.computeIfAbsent(qualifiedName, k -> ConcurrentHashMap.newKeySet())
                .add(var);

        logger.debug("Registered producer variable: {} with type {} from condition {}",
                    qualifiedName, type, conditionType);

        return var;
    }

    /**
     * Registers a consumer variable.
     *
     * @param qualifiedName The qualified variable name (e.g., $main.var, alias.var)
     * @param type The variable type
     * @param conditionType The condition type that consumes the variable
     * @return The registered consumer variable
     */
    public ConsumerVariable registerConsumer(String qualifiedName, VariableType type, String conditionType) {
        ConsumerVariable var = new ConsumerVariable(qualifiedName, type, conditionType);

        consumers.computeIfAbsent(qualifiedName, k -> ConcurrentHashMap.newKeySet())
                .add(var);

        logger.debug("Registered consumer variable: {} with type {} from condition {}",
                    qualifiedName, type, conditionType);

        return var;
    }

    /**
     * Gets all producer variables for a given qualified name.
     *
     * @param qualifiedName The qualified variable name
     * @return Unmodifiable set of producer variables
     */
    public Set<ProducerVariable> getProducers(String qualifiedName) {
        Set<ProducerVariable> result = producers.getOrDefault(qualifiedName, Collections.emptySet());
        logger.debug("getProducers('{}') returning {} producers", qualifiedName, result.size());
        return Collections.unmodifiableSet(result);
    }

    /**
     * Gets all consumer variables for a given qualified name.
     *
     * @param qualifiedName The qualified variable name
     * @return Unmodifiable set of consumer variables
     */
    public Set<ConsumerVariable> getConsumers(String qualifiedName) {
        Set<ConsumerVariable> result = consumers.getOrDefault(qualifiedName, Collections.emptySet());
        logger.debug("getConsumers('{}') returning {} consumers", qualifiedName, result.size());
        return Collections.unmodifiableSet(result);
    }

    /**
     * Checks if a variable is produced (has at least one producer).
     *
     * @param qualifiedName The qualified variable name
     * @return true if the variable is produced, false otherwise
     */
    public boolean isProduced(String qualifiedName) {
        boolean result = producers.containsKey(qualifiedName) && !producers.get(qualifiedName).isEmpty();
        logger.debug("isProduced('{}') returning {}", qualifiedName, result);
        return result;
    }

    /**
     * Gets all qualified variable names in the registry.
     *
     * @return Unmodifiable set of all qualified variable names
     */
    public Set<String> getAllVariableNames() {
        Set<String> allNames = new HashSet<>();
        allNames.addAll(producers.keySet());
        allNames.addAll(consumers.keySet());
        logger.debug("getAllVariableNames() returning {} variables: {}", allNames.size(), allNames);
        return Collections.unmodifiableSet(allNames);
    }

    /**
     * Gets all producer variables in the registry.
     *
     * @return Unmodifiable collection of all producer variables
     */
    public Collection<ProducerVariable> getAllProducers() {
        return producers.values().stream()
            .flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Gets all consumer variables in the registry.
     *
     * @return Unmodifiable collection of all consumer variables
     */
    public Collection<ConsumerVariable> getAllConsumers() {
        return consumers.values().stream()
            .flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Gets a variable by its qualified name.
     * Prioritizes producer variables if the name is registered as both producer and consumer.
     *
     * @param qualifiedName The qualified variable name (e.g., $main.var, alias.var)
     * @return An Optional containing the Variable if found, otherwise Optional.empty().
     */
    public java.util.Optional<Variable> getVariable(String qualifiedName) {
        if (producers.containsKey(qualifiedName) && !producers.get(qualifiedName).isEmpty()) {
            // Return the first producer variable found for this name
            return java.util.Optional.of(producers.get(qualifiedName).iterator().next());
        } else if (consumers.containsKey(qualifiedName) && !consumers.get(qualifiedName).isEmpty()) {
            // If not a producer, return the first consumer variable found
            return java.util.Optional.of(consumers.get(qualifiedName).iterator().next());
        }
        return java.util.Optional.empty();
    }

    /**
     * Gets the inferred type for a variable, based on all its producers and consumers.
     * If there are conflicting types, ANY is returned.
     *
     * @param qualifiedName The qualified variable name
     * @return The inferred type, or ANY if unknown or conflicting
     */
    public VariableType getInferredType(String qualifiedName) {
        // Collect specific producer types (excluding ANY)
        Set<VariableType> specificProducerTypes = producers.getOrDefault(qualifiedName, Collections.emptySet())
            .stream()
            .map(Variable::getType)
            .filter(type -> type != VariableType.ANY)
            .collect(Collectors.toSet());

        // Collect specific consumer types (excluding ANY)
        Set<VariableType> specificConsumerTypes = consumers.getOrDefault(qualifiedName, Collections.emptySet())
            .stream()
            .map(Variable::getType)
            .filter(type -> type != VariableType.ANY)
            .collect(Collectors.toSet());

        // Combine all specific types
        Set<VariableType> allSpecificTypes = new HashSet<>();
        allSpecificTypes.addAll(specificProducerTypes);
        allSpecificTypes.addAll(specificConsumerTypes);

        // If exactly one specific type exists across producers/consumers, return it
        if (allSpecificTypes.size() == 1) {
            VariableType specificType = allSpecificTypes.iterator().next();
             logger.debug("getInferredType('{}') returning single specific type: {}", qualifiedName, specificType);
            return specificType;
        }

        // If there are multiple specific types (conflict) or no specific types,
        // check if ANY was used at all. If so, the type is ANY.
        // If no specific types AND no ANY usage, default to ANY (variable exists but type unknown).
        boolean anyProducer = producers.getOrDefault(qualifiedName, Collections.emptySet())
                                .stream().anyMatch(v -> v.getType() == VariableType.ANY);
        boolean anyConsumer = consumers.getOrDefault(qualifiedName, Collections.emptySet())
                                .stream().anyMatch(v -> v.getType() == VariableType.ANY);

        if (allSpecificTypes.isEmpty() && (anyProducer || anyConsumer)) {
             logger.debug("getInferredType('{}') returning ANY (only ANY usage found)", qualifiedName);
            return VariableType.ANY;
        } else {
            // Conflict (multiple specific types) or no usage at all (result is also ANY)
             logger.debug("getInferredType('{}') returning ANY (conflict or no info)", qualifiedName);
            return VariableType.ANY;
        }
    }

    /**
     * Validates that all consumer variables have corresponding producers.
     *
     * @return Set of validation error messages, empty if valid
     */
    public Set<String> validate() {
        Set<String> errors = new HashSet<>();

        // Check that all consumed variables are produced
        for (String qualifiedName : consumers.keySet()) {
            if (!isProduced(qualifiedName)) {
                errors.add("Variable " + qualifiedName + " is consumed but never produced");
            }
        }

        logger.debug("Validate() result: {} errors", errors.size());
        if (!errors.isEmpty()) {
            logger.debug("Validation errors: {}", errors);
        }

        return errors;
    }

    /**
     * Clears all variables from the registry.
     */
    public void clear() {
        producers.clear();
        consumers.clear();
        logger.debug("Registry cleared");
    }

    /**
     * Re-qualifies all variable names in the registry from oldPrefix to newPrefix.
     * Used to map subquery variables from $main.var to alias.var.
     */
    public void requalifyVariables(String oldPrefix, String newPrefix) {
        Map<String, Set<ProducerVariable>> newProducers = new ConcurrentHashMap<>();
        for (var entry : producers.entrySet()) {
            String newKey = entry.getKey().replaceFirst("^" + java.util.regex.Pattern.quote(oldPrefix), newPrefix);
            newProducers.put(newKey, entry.getValue().stream()
                .map(v -> new ProducerVariable(newKey, v.getType(), v.sourceConditionType()))
                .collect(java.util.stream.Collectors.toSet()));
        }
        producers.clear();
        producers.putAll(newProducers);

        Map<String, Set<ConsumerVariable>> newConsumers = new ConcurrentHashMap<>();
        for (var entry : consumers.entrySet()) {
            String newKey = entry.getKey().replaceFirst("^" + java.util.regex.Pattern.quote(oldPrefix), newPrefix);
            newConsumers.put(newKey, entry.getValue().stream()
                .map(v -> new ConsumerVariable(newKey, v.getType(), v.consumingConditionType()))
                .collect(java.util.stream.Collectors.toSet()));
        }
        consumers.clear();
        consumers.putAll(newConsumers);
    }
}