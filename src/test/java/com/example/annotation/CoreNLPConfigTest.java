package com.example.annotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.Test;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.util.CoreMap;

/**
 * Tests confirming that CoreNLP maxlen properties (pos.maxlen, ner.maxlen)
 * are measured in TOKENS, not characters.
 *
 * <p>
 * This matters because {@link CoreNLPConfig#MAX_SENTENCE_LENGTH} is a
 * <em>character</em> limit (150), and the maxlen values should be set to a
 * token count that roughly corresponds to that character limit (~25-30).
 * </p>
 */
class CoreNLPConfigTest {

    /**
     * Builds a lightweight pipeline with a custom pos.maxlen.
     * Only tokenize, ssplit, and pos are used to keep the test fast.
     */
    private static StanfordCoreNLP buildPipeline(int posMaxlen) {
        Properties props = new Properties();
        props.setProperty("annotators", "tokenize,ssplit,pos");
        props.setProperty("pos.maxlen", String.valueOf(posMaxlen));
        // Use the same tokenizer options as CoreNLPConfig for consistency
        props.setProperty("tokenize.options", String.join(",",
                "ptb3Escaping=false",
                "untokenizable=noneKeep",
                "tokenizeNLs=false"));
        return new StanfordCoreNLP(props);
    }

    /**
     * Confirms that pos.maxlen is measured in tokens, not characters.
     *
     * <p>
     * We set pos.maxlen=5 and pass a document with two sentences:
     * </p>
     * <ul>
     *   <li><b>Sentence 1:</b> 4 very long words — few tokens but many
     *       characters (~140 chars).</li>
     *   <li><b>Sentence 2:</b> 12 one-letter words — many tokens but few
     *       characters (~24 chars).</li>
     * </ul>
     *
     * <p>
     * CoreNLP assigns the fallback tag {@code "X"} (unknown) to all tokens in
     * sentences that are skipped due to maxlen. This is distinct from null —
     * the tokens still exist, they just weren't POS-tagged.
     * </p>
     *
     * <p>
     * If maxlen were character-based, sentence 1 (long chars, few tokens)
     * would be skipped and get all "X" tags, while sentence 2 (short chars,
     * many tokens) would get real POS tags.
     * </p>
     *
     * <p>
     * If maxlen is token-based, sentence 1 (5 tokens ≤ 5 limit) gets real
     * POS tags, and sentence 2 (13 tokens > 5 limit) gets all "X" fallback.
     * </p>
     */
    @Test
    void testPosMaxlenIsTokenBased() {
        StanfordCoreNLP pipeline = buildPipeline(/* posMaxlen= */5);

        // Sentence 1: 4 very long words (~35 chars each) → ~140 chars, 5 tokens
        String longWord = "supercalifragilisticexpialidocious"; // 34 chars
        String sentence1 = longWord + " " + longWord + " " + longWord + " " + longWord + ".";

        // Sentence 2: 12 one-letter words → ~24 chars, 13 tokens
        String sentence2 = "I a I a I a I a I a I a .";

        String text = sentence1 + "  " + sentence2;

        Annotation document = new Annotation(text);
        pipeline.annotate(document);

        var sentences = document.get(CoreAnnotations.SentencesAnnotation.class);
        assertNotNull(sentences, "Document should have sentences");
        assertEquals(2, sentences.size(), "Should have exactly 2 sentences");

        CoreMap sent1 = sentences.get(0);
        CoreMap sent2 = sentences.get(1);

        var sent1Tokens = sent1.get(CoreAnnotations.TokensAnnotation.class);
        assertNotNull(sent1Tokens, "Sentence 1 should have tokens");
        assertEquals(5, sent1Tokens.size(), "Sentence 1 should have 5 tokens (4 long words + period)");

        var sent2Tokens = sent2.get(CoreAnnotations.TokensAnnotation.class);
        assertNotNull(sent2Tokens, "Sentence 2 should have tokens");
        assertEquals(13, sent2Tokens.size(), "Sentence 2 should have 13 tokens (12 one-letter words + period)");

        // KEY ASSERTION: If maxlen is token-based (limit=5):
        // Sentence 1 has 5 tokens (≤ limit) → real POS tags (varied, not all "X")
        // Sentence 2 has 13 tokens (> limit) → all "X" fallback (skipped)
        //
        // If maxlen were character-based:
        // Sentence 1 (~140 chars) would be skipped → all "X"
        // Sentence 2 (~24 chars) would be annotated → real POS tags

        // Sentence 1 should have been annotated: not all tokens should be "X"
        boolean sent1AllX = sent1Tokens.stream().allMatch(t -> "X".equals(t.tag()));
        assertTrue(!sent1AllX,
                "Sentence 1 (5 tokens, ~140 chars) should have real POS tags, not all 'X'. "
                        + "maxlen appears to be character-based, but we expected token-based.");

        // Sentence 2 should have been skipped: ALL tokens should be "X"
        boolean sent2AllX = sent2Tokens.stream().allMatch(t -> "X".equals(t.tag()));
        assertTrue(sent2AllX,
                "Sentence 2 (13 tokens, ~24 chars) should be SKIPPED (all 'X' fallback). "
                        + "maxlen appears to be character-based, but we expected token-based.");
    }

    /**
     * Second variant: set maxlen=2 so BOTH sentences exceed the token limit.
     * This proves that the "all X" pattern consistently indicates skipping,
     * and that maxlen doesn't treat characters as the unit.
     */
    @Test
    void testMaxlen2SkipsBothSentences() {
        StanfordCoreNLP pipeline = buildPipeline(/* posMaxlen= */2);

        String longWord = "supercalifragilisticexpialidocious";
        String sentence1 = longWord + " " + longWord + "."; // 3 tokens, ~72 chars
        String sentence2 = "I a I a ."; // 5 tokens, ~10 chars

        String text = sentence1 + "  " + sentence2;

        Annotation document = new Annotation(text);
        pipeline.annotate(document);

        var sentences = document.get(CoreAnnotations.SentencesAnnotation.class);
        assertEquals(2, sentences.size());

        // Both sentences exceed 2 tokens, so both should be skipped (all "X")
        for (int i = 0; i < 2; i++) {
            var tokens = sentences.get(i).get(CoreAnnotations.TokensAnnotation.class);
            boolean allX = tokens.stream().allMatch(t -> "X".equals(t.tag()));
            assertTrue(allX,
                    "Sentence " + (i + 1) + " (" + tokens.size() + " tokens) should be skipped (all 'X'), "
                            + "but got varied tags. maxlen=2 should skip both sentences.");
        }
    }

    /**
     * Verifies the character lengths of the test sentences match our
     * assumptions, so the test logic remains correct if the test text changes.
     */
    @Test
    void testSentenceCharacterLengths() {
        String longWord = "supercalifragilisticexpialidocious";
        String sentence1 = longWord + " " + longWord + " " + longWord + " " + longWord + ".";
        String sentence2 = "I a I a I a I a I a I a .";

        // Sentence 1 has 4×34 + 4 spaces + period = 141 chars
        assertTrue(sentence1.length() > 130,
                "Sentence 1 should be long in characters (was " + sentence1.length() + ")");
        // Sentence 2 has 12×1 + 12 spaces + period = 25 chars
        assertTrue(sentence2.length() < 30,
                "Sentence 2 should be short in characters (was " + sentence2.length() + ")");

        // Sanity: sentence 1 is shorter in tokens but much longer in characters
        assertTrue(sentence1.length() > sentence2.length(),
                "Sentence 1 should have MORE characters than sentence 2");
    }
}
