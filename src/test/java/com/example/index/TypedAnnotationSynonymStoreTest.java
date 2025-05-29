package com.example.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
// import java.util.EnumSet; // No longer needed for all types in one instance

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the TypedAnnotationSynonymStore class.
 */
public class TypedAnnotationSynonymStoreTest {

    @TempDir
    Path tempDir;

    private TypedAnnotationSynonymStore dateSynonyms;
    private TypedAnnotationSynonymStore nerSynonyms;
    private TypedAnnotationSynonymStore posSynonyms;
    private TypedAnnotationSynonymStore dependencySynonyms;

    @BeforeEach
    public void setUp() throws Exception {
        // Each type gets its own store, in its own subdirectory to mimic AbstractUnigramStitchGenerator
        Path dateDir = tempDir.resolve("stitch-date");
        Files.createDirectories(dateDir);
        dateSynonyms = new TypedAnnotationSynonymStore(dateDir.resolve("synonyms.dat"), AnnotationType.DATE);

        Path nerDir = tempDir.resolve("stitch-ner");
        Files.createDirectories(nerDir);
        nerSynonyms = new TypedAnnotationSynonymStore(nerDir.resolve("synonyms.dat"), AnnotationType.NER);

        Path posDir = tempDir.resolve("stitch-pos");
        Files.createDirectories(posDir);
        posSynonyms = new TypedAnnotationSynonymStore(posDir.resolve("synonyms.dat"), AnnotationType.POS);

        Path depDir = tempDir.resolve("stitch-dependency"); // Example path
        Files.createDirectories(depDir);
        dependencySynonyms = new TypedAnnotationSynonymStore(depDir.resolve("synonyms.dat"), AnnotationType.DEPENDENCY);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (dateSynonyms != null) dateSynonyms.close();
        if (nerSynonyms != null) nerSynonyms.close();
        if (posSynonyms != null) posSynonyms.close();
        if (dependencySynonyms != null) dependencySynonyms.close();
    }

    @Test
    public void testDateSynonyms() {
        String date1 = "2023-01-01";
        String date2 = "2022-12-25";

        int id1 = dateSynonyms.getOrCreateId(date1);
        int id2 = dateSynonyms.getOrCreateId(date2);

        assertNotEquals(id1, id2);
        assertEquals(id1, dateSynonyms.getOrCreateId(date1));
        assertEquals(id2, dateSynonyms.getOrCreateId(date2));
        assertEquals(date1, dateSynonyms.getValue(id1));
        assertEquals(date2, dateSynonyms.getValue(id2));
        assertEquals(2, dateSynonyms.size());
        assertEquals(AnnotationType.DATE, dateSynonyms.getManagedType());
    }

    @Test
    public void testNerSynonyms() {
        String ner1 = "PERSON";
        String ner2 = "LOCATION";

        int id1 = nerSynonyms.getOrCreateId(ner1);
        int id2 = nerSynonyms.getOrCreateId(ner2);

        assertNotEquals(id1, id2);
        assertEquals(id1, nerSynonyms.getOrCreateId(ner1));
        assertEquals(id2, nerSynonyms.getOrCreateId(ner2));
        assertEquals(ner1, nerSynonyms.getValue(id1));
        assertEquals(ner2, nerSynonyms.getValue(id2));
        assertEquals(2, nerSynonyms.size());
        assertEquals(AnnotationType.NER, nerSynonyms.getManagedType());
    }

    @Test
    public void testPosSynonyms() {
        String pos1 = "NN";
        String pos2 = "VB";

        int id1 = posSynonyms.getOrCreateId(pos1);
        int id2 = posSynonyms.getOrCreateId(pos2);

        assertNotEquals(id1, id2);
        assertEquals(id1, posSynonyms.getOrCreateId(pos1));
        assertEquals(id2, posSynonyms.getOrCreateId(pos2));
        assertEquals(pos1, posSynonyms.getValue(id1));
        assertEquals(pos2, posSynonyms.getValue(id2));
        assertEquals(2, posSynonyms.size());
        assertEquals(AnnotationType.POS, posSynonyms.getManagedType());
    }

    @Test
    public void testDependencySynonyms() {
        String dep1 = "nsubj";
        String dep2 = "dobj";

        int id1 = dependencySynonyms.getOrCreateId(dep1);
        int id2 = dependencySynonyms.getOrCreateId(dep2);

        assertNotEquals(id1, id2);
        assertEquals(id1, dependencySynonyms.getOrCreateId(dep1));
        assertEquals(id2, dependencySynonyms.getOrCreateId(dep2));
        assertEquals(dep1, dependencySynonyms.getValue(id1));
        assertEquals(dep2, dependencySynonyms.getValue(id2));
        assertEquals(2, dependencySynonyms.size());
        assertEquals(AnnotationType.DEPENDENCY, dependencySynonyms.getManagedType());
    }

    @Test
    public void testIdUniquenessAcrossDifferentTypesButSameValue() {
        String value = "test";

        int nerId = nerSynonyms.getOrCreateId(value);       // e.g., 1
        int posId = posSynonyms.getOrCreateId(value);       // e.g., 1 (in its own store)
        int depId = dependencySynonyms.getOrCreateId(value); // e.g., 1 (in its own store)

        // IDs will likely be the same (e.g., 1) because they are from different stores,
        // each starting its own sequence. This is expected and correct.
        assertEquals(1, nerId);
        assertEquals(1, posId);
        assertEquals(1, depId);

        // Verify values are correct for their respective stores
        assertEquals(value, nerSynonyms.getValue(nerId));
        assertEquals(value, posSynonyms.getValue(posId));
        assertEquals(value, dependencySynonyms.getValue(depId));
    }

    @Test
    public void testPersistence() throws IOException {
        String dateVal = "2024-01-20";
        int dateId = dateSynonyms.getOrCreateId(dateVal);
        dateSynonyms.close(); // Save and close

        // Reopen and check if data is still there
        // Ensure we use the correct file path for reopening
        Path dateSynonymFile = tempDir.resolve("stitch-date").resolve("synonyms.dat");
        dateSynonyms = new TypedAnnotationSynonymStore(dateSynonymFile, AnnotationType.DATE);
        assertEquals(dateId, dateSynonyms.getOrCreateId(dateVal));
        assertEquals(dateVal, dateSynonyms.getValue(dateId));
        assertEquals(1, dateSynonyms.size());
    }

    @Test
    public void testInvalidDateValue() {
        assertThrows(IllegalArgumentException.class, () ->
            dateSynonyms.getOrCreateId("not-a-date"));
        assertThrows(IllegalArgumentException.class, () ->
            dateSynonyms.getOrCreateId("2023-02-30")); // Invalid day
    }

    @Test
    public void testEmptyValue() {
        assertThrows(IllegalArgumentException.class, () -> nerSynonyms.getOrCreateId(""));
        assertThrows(IllegalArgumentException.class, () -> nerSynonyms.getOrCreateId(null));
    }
}