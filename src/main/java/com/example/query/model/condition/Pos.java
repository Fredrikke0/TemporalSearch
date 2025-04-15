package com.example.query.model.condition;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

import com.example.query.binding.VariableRegistry;
import com.example.query.binding.VariableType;

/**
 * Represents a POS (Part-of-Speech) condition in the query language.
 * This condition checks for terms with a specific POS tag, optionally binding the term.
 * 
 * **Note:** POS tags are stored and queried in lowercase.
 * 
 * Available POS tags (based on CoreNLP annotations on the Wikipedia dataset):
 * 
 * - jj: Adjective
 * - nn: Noun, singular or mass
 * - vbz: Verb, 3rd person singular present
 * - dt: Determiner
 * - rbs: Adverb, superlative
 * - hyph: Hyphen
 * - vbn: Verb, past participle
 * - in: Preposition or subordinating conjunction
 * - nnp: Proper noun, singular
 * - .: Punctuation mark, sentence closer
 * - ,: Punctuation mark, comma
 * - wdt: Wh-determiner
 * - cc: Coordinating conjunction
 * - vbd: Verb, past tense
 * - nns: Noun, plural
 * - vbg: Verb, gerund or present participle
 * - prp: Personal pronoun
 * - rb: Adverb
 * - pdt: Predeterminer
 * - prp$: Possessive pronoun
 * - cd: Cardinal number
 * - nnps: Proper noun, plural
 * - vbp: Verb, non-3rd person singular present
 * - ex: Existential there
 * - md: Modal
 * - vb: Verb, base form
 * - wrb: Wh-adverb
 * - pos: Possessive ending
 * - -lrb-: Left round bracket
 * - -rrb-: Right round bracket
 * - wp: Wh-pronoun
 * - :: Punctuation mark, colon
 * - ``: Opening double quote
 * - jjr: Adjective, comparative
 * - to: to
 * - '': Closing double quote
 * - sym: Symbol
 * - fw: Foreign word
 * - rbr: Adverb, comparative
 * - rp: Particle
 * - jjs: Adjective, superlative
 * - nfp: Numeral, fraction, percentage
 * - add: Email/URL
 * - x: Unknown
 * - uh: Interjection
 * - wp$: Possessive wh-pronoun
 * - $: Dollar sign
 * - ls: List item marker
 * - gw: Go word (e.g., goin')
 * - afx: Affix
 * 
 */
public record Pos(
    String posTag,
    String term,        // Term can be null when isVariable is true
    String variableName,
    boolean isVariable
) implements Condition {
    
    /**
     * Creates a condition with validation.
     */
    public Pos {
        Objects.requireNonNull(posTag, "posTag cannot be null");
        
        if (isVariable) {
            // When binding a variable, term can be null (extract any term with the tag)
            Objects.requireNonNull(variableName, "variableName cannot be null when isVariable is true");
        } else {
            // When searching for a specific term/tag combination, term must be provided
            Objects.requireNonNull(term, "term cannot be null when isVariable is false");
        }
        
        // No defensive copy needed for Strings
    }

    /**
     * Creates a condition with a term and POS tag (non-variable).
     *
     * @param posTag The part-of-speech tag
     * @param term The search term
     */
    public Pos(String posTag, String term) {
        this(posTag, term, null, false);
    }

    /**
     * Creates a new POS condition with variable binding.
     */
    public Pos(String posTag, String term, String variableName) {
        this(posTag, term, variableName, true);
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
        return variableName;
    }
    
    @Override
    public String getType() {
        return "POS";
    }
    
    @Override
    public Set<String> getProducedVariables() {
        return isVariable ? Set.of(variableName) : Collections.emptySet();
    }
    
    @Override
    public VariableType getProducedVariableType() {
        // Changed to reflect the "term/TAG" format - TEXT_SPAN is most appropriate
        return VariableType.TEXT_SPAN; 
    }
    
    @Override
    public void registerVariables(VariableRegistry registry) {
        if (isVariable) {
            registry.registerProducer(variableName, getProducedVariableType(), getType());
        }
    }
    
    @Override
    public String toString() {
        if (isVariable) {
            return String.format("POS(%s) AS %s", posTag, variableName);
        } else {
            return String.format("POS(%s, %s)", posTag, term);
        }
    }
} 