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
 * **Note:** POS tags are stored and queried in uppercase.
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
    String term,        // Term can be null when isVariable is true; stores plain name if variable
    String qualifiedVariableName, // Renamed from variableName
    boolean isVariable
) implements Condition {


    /**
     * Creates a condition with validation.
     */
    public Pos {

        Objects.requireNonNull(posTag, "posTag cannot be null");

        if (isVariable) {
            Objects.requireNonNull(qualifiedVariableName, "qualifiedVariableName cannot be null when isVariable is true");
            if (qualifiedVariableName.isBlank()) {
                throw new IllegalArgumentException("qualifiedVariableName cannot be blank when isVariable is true");
            }
        } else {
            if (qualifiedVariableName != null) {
                throw new IllegalArgumentException("qualifiedVariableName must be null when isVariable is false");
            }
        }

        // No defensive copy needed for Strings
    }

    /**
     * Creates a condition for a specific term and POS tag (non-variable).
     * For use with executors that support term specification.
     * PosExecutor will reject this if term is non-null.
     *
     * @param posTag The part-of-speech tag
     * @param term The search term (must be non-null for this constructor's typical use)
     */
    public Pos(String posTag, String term) {
        this(posTag, term, null, false);
        // The canonical constructor will now validate based on the modified logic.
        // If term is null here, it's now allowed by canonical if qualifiedVariableName is also null and isVariable is false.
    }

    /**
     * Creates a new POS condition with variable binding for the matched term/tag.
     * The 'term' field here is effectively ignored by PosExecutor if non-null, as it binds the tag.
     * If 'term' is null, it means bind any term with that tag.
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
        // Method kept for potential backward compatibility? Or remove?
        // Returning the qualified name here.
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

    @Override
    public String getType() {
        return "POS";
    }

    @Override
    public Set<String> getProducedVariables() {
        // Return the qualified name if bound
        return isVariable ? Set.of(qualifiedVariableName) : Collections.emptySet();
    }

    @Override
    public VariableType getProducedVariableType() {
        // Changed to reflect the "term/TAG" format - TEXT_SPAN is most appropriate
        return VariableType.TEXT_SPAN;
    }

    @Override
    public void registerVariables(VariableRegistry registry) {
        if (isVariable) {
            // Registration now happens in QueryModelBuilder with qualified name
            // registry.registerProducer(qualifiedVariableName, getProducedVariableType(), getType());
        }
        // Consumption of 'term' if it were a variable would also be handled in builder
    }

    @Override
    public String toString() {
        if (isVariable) {
            // Format: POS(Tag) BIND alias.var
            return String.format("POS(%s) BIND %s", posTag, qualifiedVariableName);
        } else if (term == null) {
            return String.format("POS(%s)", posTag);
        } else {
            return String.format("POS(%s, %s)", posTag, term);
        }
    }

    /**
     * Creates a new Pos condition with the variable name requalified.
     * This is used during subquery processing when variable names need to be updated
     * from one alias scope to another (e.g., from "$main.pos" to "q2.pos").
     *
     * @param oldPrefix The old prefix to replace (e.g., "$main.")
     * @param newPrefix The new prefix to use (e.g., "q2.")
     * @return A new Pos condition with the requalified variable name, or this condition if no change needed
     */
    public Pos requalifyVariable(String oldPrefix, String newPrefix) {
        if (!isVariable || qualifiedVariableName == null) {
            return this; // No variable to requalify
        }

        if (!qualifiedVariableName.startsWith(oldPrefix)) {
            return this; // Variable doesn't match the old prefix
        }

        String newVarName = newPrefix + qualifiedVariableName.substring(oldPrefix.length());
        return new Pos(this.posTag, this.term, newVarName, this.isVariable);
    }
}