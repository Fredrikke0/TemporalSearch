package com.example.query.model.condition;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    String term,        // Term can be null when isVariable is true; stores plain name if variable
    String qualifiedVariableName, // Renamed from variableName
    boolean isVariable
) implements Condition {

    private static final Logger logger = LoggerFactory.getLogger(Pos.class);

    /**
     * Creates a condition with validation.
     */
    public Pos {
        logger.trace("Primary Pos constructor invoked with: posTag='{}', term='{}', qualifiedVariableName='{}', isVariable={}",
                     posTag, term, qualifiedVariableName, isVariable);

        Objects.requireNonNull(posTag, "posTag cannot be null");

        if (isVariable) {
            Objects.requireNonNull(qualifiedVariableName, "qualifiedVariableName cannot be null when isVariable is true");
            if (qualifiedVariableName.isBlank()) {
                throw new IllegalArgumentException("qualifiedVariableName cannot be blank when isVariable is true");
            }
            // Term can be null (e.g. POS(tag) BIND ?var - term is irrelevant for binding value which is the tag)
            // or term could be a variable name if another executor type consumes it (e.g. POS(tag, ?textVar))
        } else {
            // If not a variable binding, then qualifiedVariableName must be null.
            if (qualifiedVariableName != null) {
                throw new IllegalArgumentException("qualifiedVariableName must be null when isVariable is false");
            }
            // For PosExecutor: term must be null (it throws if term is not null).
            // For other potential executors using POS(tag, 'literal'): term would be non-null.
            // The check `Objects.requireNonNull(term, "term cannot be null when isVariable is false");` is removed
            // to allow PosExecutor to work with Pos conditions representing POS(tag).
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
        logger.trace("Pos(String posTag, String term) constructor invoked with: posTag='{}', term='{}'. Delegates to primary.", posTag, term);
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
        logger.trace("Pos(String posTag, String term, String variableName) constructor invoked with: posTag='{}', term='{}', variableName='{}'. Delegates to primary.",
                     posTag, term, variableName);
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
        } else {
            return String.format("POS(%s, %s)", posTag, term);
        }
    }
}