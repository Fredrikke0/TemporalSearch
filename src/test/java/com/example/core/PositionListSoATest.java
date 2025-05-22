package com.example.core;

import com.example.index.AnnotationType;
import com.example.index.StitchPosition;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;

class PositionListSoATest {

    private Position createSimplePosition(int docId, int sentId, int begin, int end) {
        return new Position(docId, sentId, begin, end);
    }

    private StitchPosition createStitchPosition(int docId, int sentId, int begin, int end, AnnotationType type, int synId) {
        // StitchPosition constructor also takes annotationBegin/EndChar, which are not stored in PositionListSoA.
        // For testing PositionListSoA, these can be dummy values like -1, as PositionListSoA doesn't use them directly.
        return new StitchPosition(docId, sentId, begin, end, type, synId, -1, -1);
    }

    @Test
    void testEmptyConstructor() {
        PositionListSoA pl = new PositionListSoA();
        assertEquals(0, pl.getNumPositions());
        assertTrue(pl.isEmpty());
        assertNotNull(pl.getDocumentIds());
        assertNotNull(pl.getSentenceIds());
        assertNotNull(pl.getBeginChars());
        assertNotNull(pl.getEndChars());
        assertNotNull(pl.getSynonymIds());
        assertEquals(0, pl.getDocumentIds().size());
        assertEquals(0, pl.getSynonymIds().size());
    }

    @Test
    void testAddNonStitchPosition_PrimitiveArgs() {
        PositionListSoA pl = new PositionListSoA();
        pl.add(1, 2, 10, 20);
        assertEquals(1, pl.getNumPositions());
        assertFalse(pl.isEmpty());
        assertEquals(1, pl.getDocIdAt(0));
        assertEquals(2, pl.getSentenceIdAt(0));
        assertEquals(10, pl.getBeginCharAt(0));
        assertEquals(20, pl.getEndCharAt(0));
        assertEquals(-1, pl.getSynonymIdAt(0)); // Key check for non-stitch
    }

    @Test
    void testAddStitchPosition_PrimitiveArgs() {
        PositionListSoA pl = new PositionListSoA();
        pl.add(1, 2, 10, 20, 1001);
        assertEquals(1, pl.getNumPositions());
        assertEquals(1001, pl.getSynonymIdAt(0));
    }

    @Test
    void testAdd_BasePositionObject() {
        PositionListSoA pl = new PositionListSoA();
        Position pos = createSimplePosition(1,2,10,20);
        pl.add(pos);
        assertEquals(1, pl.getNumPositions());
        assertEquals(1, pl.getDocIdAt(0));
        assertEquals(2, pl.getSentenceIdAt(0));
        assertEquals(10, pl.getBeginCharAt(0));
        assertEquals(20, pl.getEndCharAt(0));
        assertEquals(-1, pl.getSynonymIdAt(0));
    }

    @Test
    void testAdd_StitchPositionObject() {
        PositionListSoA pl = new PositionListSoA();
        // AnnotationType needed for StitchPosition constructor, but not directly used by PositionListSoA itself anymore.
        StitchPosition stitchPos = createStitchPosition(1, 2, 10, 20, AnnotationType.NER, 1002);
        pl.add(stitchPos);
        assertEquals(1, pl.getNumPositions());
        assertEquals(1, pl.getDocIdAt(0));
        assertEquals(2, pl.getSentenceIdAt(0));
        assertEquals(10, pl.getBeginCharAt(0));
        assertEquals(20, pl.getEndCharAt(0));
        assertEquals(1002, pl.getSynonymIdAt(0));
    }

    @Test
    void testGetPositionAt_NonStitch() {
        PositionListSoA pl = new PositionListSoA();
        pl.add(1,2,10,20); // synonymId will be -1
        Position pos = pl.getPositionAt(0);
        assertNotNull(pos);
        assertFalse(pos instanceof StitchPosition);
        assertEquals(1, pos.getDocumentId());
        assertEquals(2, pos.getSentenceId());
        assertEquals(10, pos.getBeginPosition());
        assertEquals(20, pos.getEndPosition());
        // To get synonymId, must call pl.getSynonymIdAt(0)
        assertEquals(-1, pl.getSynonymIdAt(0));
    }

    @Test
    void testGetPositionAt_StitchDataReturnsBasePosition() {
        PositionListSoA pl = new PositionListSoA();
        pl.add(1,2,10,20, 1003); // Add as stitch data
        Position pos = pl.getPositionAt(0);
        assertNotNull(pos); // getPositionAt always returns base Position
        assertFalse(pos instanceof StitchPosition);
        assertEquals(1, pos.getDocumentId());
        assertEquals(1003, pl.getSynonymIdAt(0)); // Verify synonymId separately
    }

    @Test
    void testIndexOutOfBounds() {
        PositionListSoA pl = new PositionListSoA();
        assertThrows(IndexOutOfBoundsException.class, () -> pl.getDocIdAt(0));
        pl.add(1,1,1,1);
        assertDoesNotThrow(() -> pl.getDocIdAt(0));
        assertThrows(IndexOutOfBoundsException.class, () -> pl.getDocIdAt(1));
    }

    // --- Serialization Tests ---
    @Test
    void testSerializeDeserialize_EmptyList() throws IOException {
        PositionListSoA plOriginal = new PositionListSoA();
        byte[] blob = plOriginal.serializeToCompositeBlob();
        assertNotNull(blob);
        // numPositions (int) = 4 bytes
        // Each of 5 arrays will write an int(0) as length marker = 5 * 4 = 20 bytes
        // Total = 4 + 20 = 24 bytes
        assertEquals(24, blob.length); 

        PositionListSoA plDeserialized = PositionListSoA.deserializeFromCompositeBlob(blob);
        assertNotNull(plDeserialized);
        assertEquals(0, plDeserialized.getNumPositions());
        assertTrue(plDeserialized.isEmpty());
    }

    private void assertSoaListsEqual(PositionListSoA expected, PositionListSoA actual) {
        assertEquals(expected.getNumPositions(), actual.getNumPositions());
        for (int i = 0; i < expected.getNumPositions(); i++) {
            assertEquals(expected.getDocIdAt(i), actual.getDocIdAt(i), "DocId at index " + i);
            assertEquals(expected.getSentenceIdAt(i), actual.getSentenceIdAt(i), "SentId at index " + i);
            assertEquals(expected.getBeginCharAt(i), actual.getBeginCharAt(i), "BeginChar at index " + i);
            assertEquals(expected.getEndCharAt(i), actual.getEndCharAt(i), "EndChar at index " + i);
            assertEquals(expected.getSynonymIdAt(i), actual.getSynonymIdAt(i), "SynonymId at index " + i);
        }
    }

    @Test
    void testSerializeDeserialize_SingleNonStitch() throws IOException {
        PositionListSoA plOriginal = new PositionListSoA();
        plOriginal.add(10, 20, 30, 40);

        byte[] blob = plOriginal.serializeToCompositeBlob();
        PositionListSoA plDeserialized = PositionListSoA.deserializeFromCompositeBlob(blob);
        assertSoaListsEqual(plOriginal, plDeserialized);
    }

    @Test
    void testSerializeDeserialize_SingleStitch() throws IOException {
        PositionListSoA plOriginal = new PositionListSoA();
        plOriginal.add(10, 20, 30, 40, 12345);

        byte[] blob = plOriginal.serializeToCompositeBlob();
        PositionListSoA plDeserialized = PositionListSoA.deserializeFromCompositeBlob(blob);
        assertSoaListsEqual(plOriginal, plDeserialized);
    }

    @Test
    void testSerializeDeserialize_MultipleMixedPositions() throws IOException {
        PositionListSoA plOriginal = new PositionListSoA();
        plOriginal.add(1, 1, 1, 2);          // Non-stitch
        plOriginal.add(1, 1, 5, 6, 101);    // Stitch
        plOriginal.add(2, 1, 10, 12);         // Non-stitch
        plOriginal.add(2, 1, 15, 16, 102);   // Stitch

        byte[] blob = plOriginal.serializeToCompositeBlob();
        PositionListSoA plDeserialized = PositionListSoA.deserializeFromCompositeBlob(blob);
        assertSoaListsEqual(plOriginal, plDeserialized);
    }

    @Test
    void testSerializeDeserialize_ManyPositionsForCompression() throws IOException {
        PositionListSoA plOriginal = new PositionListSoA();
        for (int i = 0; i < 300; i++) { // More than UNCOMPRESSED_THRESHOLD
            if (i % 2 == 0) {
                plOriginal.add(i / 10, i % 10, i * 2, i * 2 + 5); 
            } else {
                plOriginal.add(i / 10, i % 10, i * 2, i * 2 + 5, 1000 + i);
            }
        }
        byte[] blob = plOriginal.serializeToCompositeBlob();
        PositionListSoA plDeserialized = PositionListSoA.deserializeFromCompositeBlob(blob);
        assertSoaListsEqual(plOriginal, plDeserialized);
        assertTrue(blob.length < (long)plOriginal.getNumPositions() * 5 * 4, "Blob should be compressed"); // Rough check
    }

    // --- Selective Deserialization Tests ---
    @Test
    void testGetNumPositionsFromBlob() throws IOException {
        PositionListSoA pl = new PositionListSoA();
        assertEquals(0, PositionListSoA.getNumPositionsFromBlob(pl.serializeToCompositeBlob()));
        pl.add(1,1,1,1);
        assertEquals(1, PositionListSoA.getNumPositionsFromBlob(pl.serializeToCompositeBlob()));
        pl.add(2,2,2,2,202);
        assertEquals(2, PositionListSoA.getNumPositionsFromBlob(pl.serializeToCompositeBlob()));
    }

    @Test
    void testSelectiveDecompression() throws IOException {
        PositionListSoA plOriginal = new PositionListSoA();
        plOriginal.add(1, 10, 100, 1000);          
        plOriginal.add(2, 20, 200, 2000, 20000);   
        plOriginal.add(3, 30, 300, 3000); 

        byte[] blob = plOriginal.serializeToCompositeBlob();

        IntArrayList docIds = PositionListSoA.decompressDocIds(blob);
        assertEquals(3, docIds.size());
        assertEquals(1, docIds.getInt(0));
        assertEquals(2, docIds.getInt(1));
        assertEquals(3, docIds.getInt(2));

        IntArrayList sentIds = PositionListSoA.decompressSentenceIds(blob);
        assertEquals(3, sentIds.size());
        assertEquals(10, sentIds.getInt(0));
        assertEquals(20, sentIds.getInt(1));
        assertEquals(30, sentIds.getInt(2));

        IntArrayList beginChars = PositionListSoA.decompressBeginChars(blob);
        assertEquals(3, beginChars.size());
        assertEquals(100, beginChars.getInt(0));
        assertEquals(200, beginChars.getInt(1));
        assertEquals(300, beginChars.getInt(2));

        IntArrayList endChars = PositionListSoA.decompressEndChars(blob);
        assertEquals(3, endChars.size());
        assertEquals(1000, endChars.getInt(0));
        assertEquals(2000, endChars.getInt(1));
        assertEquals(3000, endChars.getInt(2));

        IntArrayList synIds = PositionListSoA.decompressSynonymIds(blob);
        assertEquals(3, synIds.size());
        assertEquals(-1, synIds.getInt(0));
        assertEquals(20000, synIds.getInt(1));
        assertEquals(-1, synIds.getInt(2));
    }
    
    // --- Manipulation Method Tests ---
    @Test
    void testClearAndIsEmpty() {
        PositionListSoA pl = new PositionListSoA();
        assertTrue(pl.isEmpty());
        pl.add(1,1,1,1);
        assertFalse(pl.isEmpty());
        pl.clear();
        assertTrue(pl.isEmpty());
        assertEquals(0, pl.getNumPositions());
        // Check that internal lists are cleared (though they are replaced in some ops like sort/merge)
        assertEquals(0, pl.getDocumentIds().size()); 
    }

    @Test
    void testAddAll() {
        PositionListSoA pl1 = new PositionListSoA();
        pl1.add(1,1,1,2);
        pl1.add(1,1,5,6,101);

        PositionListSoA pl2 = new PositionListSoA();
        pl2.add(2,1,10,12);
        pl2.add(2,1,15,16,102);

        pl1.addAll(pl2);
        assertEquals(4, pl1.getNumPositions());
        assertEquals(2, pl1.getDocIdAt(2));
        assertEquals(102, pl1.getSynonymIdAt(3));

        PositionListSoA pl3 = new PositionListSoA();
        pl1.addAll(pl3); // Add empty
        assertEquals(4, pl1.getNumPositions());

        pl3.addAll(pl1); // Add to empty
        assertEquals(4, pl3.getNumPositions());
        assertEquals(101, pl3.getSynonymIdAt(1));
    }
    
    @Test
    void testSort() {
        PositionListSoA pl = new PositionListSoA();
        pl.add(2,1,10,20);      // sId: -1
        pl.add(1,1,5,6, 101);   // sId: 101
        pl.add(1,1,1,2);      // sId: -1
        pl.add(2,1,1,5, 102);    // sId: 102
        pl.add(1,2,1,5);      // sId: -1

        pl.sort();

        // Expected order:
        // (1,1,1,2,-1)
        // (1,1,5,6,101)
        // (1,2,1,5,-1)
        // (2,1,1,5,102)
        // (2,1,10,20,-1)

        assertEquals(1, pl.getDocIdAt(0)); assertEquals(1, pl.getSentenceIdAt(0)); assertEquals(1, pl.getBeginCharAt(0)); assertEquals(2, pl.getEndCharAt(0)); assertEquals(-1, pl.getSynonymIdAt(0));
        assertEquals(1, pl.getDocIdAt(1)); assertEquals(1, pl.getSentenceIdAt(1)); assertEquals(5, pl.getBeginCharAt(1)); assertEquals(6, pl.getEndCharAt(1)); assertEquals(101, pl.getSynonymIdAt(1));
        assertEquals(1, pl.getDocIdAt(2)); assertEquals(2, pl.getSentenceIdAt(2)); assertEquals(1, pl.getBeginCharAt(2)); assertEquals(5, pl.getEndCharAt(2)); assertEquals(-1, pl.getSynonymIdAt(2));
        assertEquals(2, pl.getDocIdAt(3)); assertEquals(1, pl.getSentenceIdAt(3)); assertEquals(1, pl.getBeginCharAt(3)); assertEquals(5, pl.getEndCharAt(3)); assertEquals(102, pl.getSynonymIdAt(3));
        assertEquals(2, pl.getDocIdAt(4)); assertEquals(1, pl.getSentenceIdAt(4)); assertEquals(10, pl.getBeginCharAt(4)); assertEquals(20, pl.getEndCharAt(4)); assertEquals(-1, pl.getSynonymIdAt(4));
    }

    @Test
    void testMerge() {
        PositionListSoA pl1 = new PositionListSoA();
        pl1.add(1,1,1,2);      
        pl1.add(1,1,5,6, 101);  
        pl1.add(2,1,10,12);     

        PositionListSoA pl2 = new PositionListSoA();
        pl2.add(1,1,5,6, 101); // Duplicate of one in pl1
        pl2.add(2,1,15,16,102); 
        pl2.add(3,1,1,1);       

        pl1.merge(pl2);

        // Expected (sorted and unique):
        // (1,1,1,2,-1)
        // (1,1,5,6,101)
        // (2,1,10,12,-1)
        // (2,1,15,16,102)
        // (3,1,1,1,-1)
        assertEquals(5, pl1.getNumPositions());
        assertEquals(1, pl1.getDocIdAt(0)); assertEquals(1, pl1.getSentenceIdAt(0)); assertEquals(1, pl1.getBeginCharAt(0)); assertEquals(2, pl1.getEndCharAt(0)); assertEquals(-1, pl1.getSynonymIdAt(0));
        assertEquals(1, pl1.getDocIdAt(1)); assertEquals(1, pl1.getSentenceIdAt(1)); assertEquals(5, pl1.getBeginCharAt(1)); assertEquals(6, pl1.getEndCharAt(1)); assertEquals(101, pl1.getSynonymIdAt(1));
        assertEquals(2, pl1.getDocIdAt(2)); assertEquals(1, pl1.getSentenceIdAt(2)); assertEquals(10, pl1.getBeginCharAt(2)); assertEquals(12, pl1.getEndCharAt(2)); assertEquals(-1, pl1.getSynonymIdAt(2));
        assertEquals(2, pl1.getDocIdAt(3)); assertEquals(1, pl1.getSentenceIdAt(3)); assertEquals(15, pl1.getBeginCharAt(3)); assertEquals(16, pl1.getEndCharAt(3)); assertEquals(102, pl1.getSynonymIdAt(3));
        assertEquals(3, pl1.getDocIdAt(4)); assertEquals(1, pl1.getSentenceIdAt(4)); assertEquals(1, pl1.getBeginCharAt(4)); assertEquals(1, pl1.getEndCharAt(4)); assertEquals(-1, pl1.getSynonymIdAt(4));
    
        PositionListSoA plEmpty = new PositionListSoA();
        PositionListSoA plToMerge = new PositionListSoA();
        plToMerge.add(1,1,1,1);
        plEmpty.merge(plToMerge);
        assertEquals(1, plEmpty.getNumPositions());

        plToMerge.merge(new PositionListSoA()); // Merge with empty
        assertEquals(1, plToMerge.getNumPositions());
    }
    
    @Test
    void testPositionIterator() {
        PositionListSoA pl = new PositionListSoA();
        pl.add(1,1,10,11);
        pl.add(2,2,20,22,202);

        Iterator<Position> it = pl.positionIterator();
        assertTrue(it.hasNext());
        Position p1 = it.next();
        assertEquals(1, p1.getDocumentId());
        assertEquals(10, p1.getBeginPosition());

        assertTrue(it.hasNext());
        Position p2 = it.next();
        assertEquals(2, p2.getDocumentId());
        assertEquals(20, p2.getBeginPosition());
        // Note: p2 is a base Position, synonymId is accessed via pl.getSynonymIdAt(1)

        assertFalse(it.hasNext());
        assertThrows(NoSuchElementException.class, it::next);
    }

    @Test
    void testTrimToSize_NoException() {
        PositionListSoA pl = new PositionListSoA();
        pl.add(1,1,1,1);
        pl.add(2,2,2,2,200);
        assertDoesNotThrow(() -> pl.trimToSize());
        assertEquals(2, pl.getNumPositions()); // Ensure list is still valid
    }

} 