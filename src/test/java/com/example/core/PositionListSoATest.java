package com.example.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import com.example.index.AnnotationType;
import com.example.index.StitchPosition;

import it.unimi.dsi.fastutil.ints.IntArrayList;

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
        PositionListSoA list = new PositionListSoA();
        byte[] blob = list.serializeToCompositeBlob();

        // Expected size: numPositions (4) + 5 * (writeInt(0) for empty array data (4 bytes each))
        // 4 + 5 * 4 = 24 bytes
        assertEquals(24, blob.length, "Serialized blob length for empty list");

        PositionListSoA deserializedList = PositionListSoA.deserializeFromCompositeBlob(blob);
        assertNotNull(deserializedList);
        assertEquals(0, deserializedList.getNumPositions());
        assertTrue(deserializedList.isEmpty());
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

    @Test
    void testSerializeDeserialize_SmallStitchLikeData() throws IOException {
        PositionListSoA plOriginal = new PositionListSoA();
        // Simulates data for a term like 'quick' that might appear in a stitch index
        // Doc 1, Sent 0, Pos 10-15, SynID 1001 (e.g., 'quick' itself)
        plOriginal.add(1, 0, 10, 15, 1001);
        // Doc 1, Sent 0, Pos 20-25, SynID -1 (e.g., 'brown' that is part of a stitch but 'brown' itself is not the primary term for this list)
        // Or, a non-stitch position that somehow got associated with this term temporarily before merging.
        plOriginal.add(1, 0, 20, 25, -1);
        // Doc 2, Sent 1, Pos 5-10, SynID 1002
        plOriginal.add(2, 1, 5, 10, 1002);

        assertEquals(3, plOriginal.getNumPositions());

        byte[] blob = plOriginal.serializeToCompositeBlob();
        assertNotNull(blob);
        assertTrue(blob.length > 0);

        PositionListSoA plDeserialized = PositionListSoA.deserializeFromCompositeBlob(blob);
        assertNotNull(plDeserialized);
        assertSoaListsEqual(plOriginal, plDeserialized);
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

    @Test
    void testSerializationUsesRLEForConstantSynonymIds() throws IOException {
        final int NUM_POSITIONS = 200; // Ensure this is > UNCOMPRESSED_THRESHOLD (20)

        // Scenario 1: All synonym IDs are -1 (typical for non-stitch list due to 4-arg add)
        // RLE should be applied to the synonymIds array.
        PositionListSoA listWithRLE_NegativeOne = new PositionListSoA();
        for (int i = 0; i < NUM_POSITIONS; i++) {
            listWithRLE_NegativeOne.add(i, i % 10, i * 10, i * 10 + 5); // synonymId will be -1
        }

        byte[] rleBlob_NegativeOne = listWithRLE_NegativeOne.serializeToCompositeBlob();
        PositionListSoA deserializedListWithRLE_NegativeOne = PositionListSoA.deserializeFromCompositeBlob(rleBlob_NegativeOne);
        assertSoaListsEqual(listWithRLE_NegativeOne, deserializedListWithRLE_NegativeOne);

        // Scenario 2: Synonym IDs vary.
        // RLE should NOT be applied to synonymIds array. Since applyDelta=false for synonymIds and they are not constant,
        // they will be written raw (uncompressed).
        PositionListSoA listWithoutRLEForSynonyms = new PositionListSoA();
        for (int i = 0; i < NUM_POSITIONS; i++) {
            // Use same doc/sent/begin/end as above to isolate synonymId's impact
            listWithoutRLEForSynonyms.add(i, i % 10, i * 10, i * 10 + 5, 1000 + i); // Varying synonymId
        }

        byte[] nonRleSynonymBlob = listWithoutRLEForSynonyms.serializeToCompositeBlob();
        PositionListSoA deserializedListWithoutRLE = PositionListSoA.deserializeFromCompositeBlob(nonRleSynonymBlob);
        assertSoaListsEqual(listWithoutRLEForSynonyms, deserializedListWithoutRLE);

        // Assert that the RLE blob is smaller.
        // The synonymIds array in rleBlob_NegativeOne (all -1s) should be RLE'd (approx 8 bytes data part + overhead).
        // The synonymIds array in nonRleSynonymBlob (varying) should be raw uncompressed (NUM_POSITIONS * 4 bytes data + overhead).
        assertTrue(rleBlob_NegativeOne.length < nonRleSynonymBlob.length,
                "Blob with RLE for constant synonymIds (-1) should be smaller than blob with raw uncompressed for varying synonymIds. " +
                "RLE (-1) size: " + rleBlob_NegativeOne.length + ", Non-RLE (varying) size: " + nonRleSynonymBlob.length);

        // Scenario 3: All synonym IDs are a constant positive value (e.g., 777)
        // RLE should also be applied here.
        PositionListSoA listWithRLE_ConstantPositive = new PositionListSoA();
        for (int i = 0; i < NUM_POSITIONS; i++) {
             listWithRLE_ConstantPositive.add(i, i % 10, i * 10, i * 10 + 5, 777); // Constant positive synonymId
        }
        byte[] rleBlob_ConstantPositive = listWithRLE_ConstantPositive.serializeToCompositeBlob();
        PositionListSoA deserializedListWithRLE_ConstantPositive = PositionListSoA.deserializeFromCompositeBlob(rleBlob_ConstantPositive);
        assertSoaListsEqual(listWithRLE_ConstantPositive, deserializedListWithRLE_ConstantPositive);

        // Its length should be very similar to rleBlob_NegativeOne (all -1s).
        // Since other attributes (docId, sentId, begin, end) are identical to listWithRLE_NegativeOne,
        // their compressed sizes should be identical. The RLE part for synonymIds should also be identical in size
        // (originalLength + RLE_MARKER_int + value_int).
        assertEquals(rleBlob_NegativeOne.length, rleBlob_ConstantPositive.length,
                "Blobs for RLE with different constant synonym values (-1 vs 777) should be the same size " +
                "if other attributes are identical. Size(-1): " + rleBlob_NegativeOne.length + ", Size(777): " + rleBlob_ConstantPositive.length);
    }

    @Test
    void testMergeCompressedBlobs() throws IOException {
        // Test merging null/empty blobs
        assertNotNull(PositionListSoA.mergeCompressedBlobs(null, null));

        PositionListSoA emptyList = new PositionListSoA();
        byte[] emptyBlob = emptyList.serializeToCompositeBlob();

        // Merging null with non-empty should return the non-empty blob
        PositionListSoA list1 = new PositionListSoA();
        list1.add(1, 1, 10, 20);
        byte[] blob1 = list1.serializeToCompositeBlob();

        byte[] result1 = PositionListSoA.mergeCompressedBlobs(null, blob1);
        assertEquals(blob1.length, result1.length);
        PositionListSoA deserialized1 = PositionListSoA.deserializeFromCompositeBlob(result1);
        assertSoaListsEqual(list1, deserialized1);

        // Merging non-empty with null should return the non-empty blob
        byte[] result2 = PositionListSoA.mergeCompressedBlobs(blob1, null);
        assertEquals(blob1.length, result2.length);
        PositionListSoA deserialized2 = PositionListSoA.deserializeFromCompositeBlob(result2);
        assertSoaListsEqual(list1, deserialized2);

        // Merging empty with non-empty
        byte[] result3 = PositionListSoA.mergeCompressedBlobs(emptyBlob, blob1);
        PositionListSoA deserialized3 = PositionListSoA.deserializeFromCompositeBlob(result3);
        assertSoaListsEqual(list1, deserialized3);

        // Test merging two non-empty blobs
        PositionListSoA list2 = new PositionListSoA();
        list2.add(2, 1, 30, 40, 1001);
        list2.add(3, 2, 50, 60);
        byte[] blob2 = list2.serializeToCompositeBlob();

        byte[] mergedBlob = PositionListSoA.mergeCompressedBlobs(blob1, blob2);
        PositionListSoA mergedList = PositionListSoA.deserializeFromCompositeBlob(mergedBlob);

        // Should have combined positions from both lists
        assertEquals(3, mergedList.getNumPositions());

        // First position from list1
        assertEquals(1, mergedList.getDocIdAt(0));
        assertEquals(1, mergedList.getSentenceIdAt(0));
        assertEquals(10, mergedList.getBeginCharAt(0));
        assertEquals(20, mergedList.getEndCharAt(0));
        assertEquals(-1, mergedList.getSynonymIdAt(0));

        // First position from list2
        assertEquals(2, mergedList.getDocIdAt(1));
        assertEquals(1, mergedList.getSentenceIdAt(1));
        assertEquals(30, mergedList.getBeginCharAt(1));
        assertEquals(40, mergedList.getEndCharAt(1));
        assertEquals(1001, mergedList.getSynonymIdAt(1));

        // Second position from list2
        assertEquals(3, mergedList.getDocIdAt(2));
        assertEquals(2, mergedList.getSentenceIdAt(2));
        assertEquals(50, mergedList.getBeginCharAt(2));
        assertEquals(60, mergedList.getEndCharAt(2));
        assertEquals(-1, mergedList.getSynonymIdAt(2));

        // Test that merging is commutative (order shouldn't matter for final content)
        byte[] mergedBlob2 = PositionListSoA.mergeCompressedBlobs(blob2, blob1);
        PositionListSoA mergedList2 = PositionListSoA.deserializeFromCompositeBlob(mergedBlob2);
        assertEquals(3, mergedList2.getNumPositions());
        // The positions should be the same, just in different order since we append blob2 first
        assertEquals(2, mergedList2.getDocIdAt(0)); // First from list2
        assertEquals(3, mergedList2.getDocIdAt(1)); // Second from list2
        assertEquals(1, mergedList2.getDocIdAt(2)); // From list1
    }

    @Test
    void testMergeCompressedBlobs_LargeData() throws IOException {
        PositionListSoA list1 = new PositionListSoA();
        for (int i = 0; i < 5000; i++) { // Increased size
            list1.add(1, i, i * 10, i * 10 + 5, (i % 3 == 0) ? -1 : 1000 + i);
        }

        PositionListSoA list2 = new PositionListSoA();
        for (int i = 0; i < 60000; i++) { // Increased size, different
            list2.add(2, i, i * 12, i * 12 + 6, (i % 4 == 0) ? -1 : 2000 + i);
        }

        byte[] blob1 = list1.serializeToCompositeBlob();
        byte[] blob2 = list2.serializeToCompositeBlob();

        byte[] mergedBlob = PositionListSoA.mergeCompressedBlobs(blob1, blob2);
        PositionListSoA mergedList = PositionListSoA.deserializeFromCompositeBlob(mergedBlob);

        assertEquals(list1.getNumPositions() + list2.getNumPositions(), mergedList.getNumPositions());

        // Basic check: Ensure all elements from list1 are present (order might change after a real merge with sorting)
        // This test as-is primarily checks if merging two distinct docId sets works.
        // A more robust check would involve sorting and comparing element-wise if merge included sorting.
        // However, mergeCompressedBlobs is an append, so we can check sequentially for original elements.
        for (int i = 0; i < list1.getNumPositions(); i++) {
            assertEquals(list1.getDocIdAt(i), mergedList.getDocIdAt(i));
            assertEquals(list1.getSentenceIdAt(i), mergedList.getSentenceIdAt(i));
            assertEquals(list1.getSynonymIdAt(i), mergedList.getSynonymIdAt(i));
        }
        for (int i = 0; i < list2.getNumPositions(); i++) {
            assertEquals(list2.getDocIdAt(i), mergedList.getDocIdAt(list1.getNumPositions() + i));
            assertEquals(list2.getSentenceIdAt(i), mergedList.getSentenceIdAt(list1.getNumPositions() + i));
            assertEquals(list2.getSynonymIdAt(i), mergedList.getSynonymIdAt(list1.getNumPositions() + i));
        }
    }

    @Test
    void benchmarkMergeStrategies() throws IOException {
        final int NUM_POSITIONS_PER_LIST = 750_000; // Number of positions for each list

        PositionListSoA listA = new PositionListSoA();
        for (int i = 0; i < NUM_POSITIONS_PER_LIST; i++) {
            listA.add(100 + i % 10, i, i * 10, i * 10 + (i % 5 + 1), (i % 7 == 0) ? -1 : 5000 + i);
        }

        PositionListSoA listB = new PositionListSoA();
        for (int i = 0; i < NUM_POSITIONS_PER_LIST; i++) {
            listB.add(200 + i % 12, i, i * 11, i * 11 + (i % 6 + 1), (i % 8 == 0) ? -1 : 6000 + i);
        }

        byte[] blobA = listA.serializeToCompositeBlob();
        byte[] blobB = listB.serializeToCompositeBlob();

        // --- Test Strategy 1: mergeCompressedBlobs (selective decompression) ---
        long startTimeSelective = System.nanoTime();
        byte[] mergedBlobSelective = PositionListSoA.mergeCompressedBlobs(blobA, blobB);
        long endTimeSelective = System.nanoTime();
        PositionListSoA resultListSelective = PositionListSoA.deserializeFromCompositeBlob(mergedBlobSelective);
        long durationSelectiveMs = (endTimeSelective - startTimeSelective) / 1_000_000;
        System.out.printf("Merge Strategy 'mergeCompressedBlobs' (Selective Decompression) took: %d ms%n", durationSelectiveMs);

        // --- Test Strategy 2: mergeBlobsByFullDeserialization (full deserialization) ---
        long startTimeFull = System.nanoTime();
        byte[] mergedBlobFull = PositionListSoA.mergeBlobsByFullDeserialization(blobA, blobB);
        long endTimeFull = System.nanoTime();
        PositionListSoA resultListFull = PositionListSoA.deserializeFromCompositeBlob(mergedBlobFull);
        long durationFullMs = (endTimeFull - startTimeFull) / 1_000_000;
        System.out.printf("Merge Strategy 'mergeBlobsByFullDeserialization' (Full Deserialization) took: %d ms%n", durationFullMs);

        // --- Verification ---
        assertEquals(NUM_POSITIONS_PER_LIST * 2, resultListSelective.getNumPositions(), "Selective merge count mismatch");
        assertEquals(NUM_POSITIONS_PER_LIST * 2, resultListFull.getNumPositions(), "Full merge count mismatch");

        // Assert that the contents are the same. Since addAll is used, order should be preserved.
        // PositionListSoA does not override equals, so we need to compare elements.
        assertSoaListsEqual(resultListSelective, resultListFull);

        System.out.println("Benchmark complete. Both methods produced equivalent merged lists.");
    }
}