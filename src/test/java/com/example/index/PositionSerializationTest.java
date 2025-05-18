package com.example.index;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import com.example.core.Position;
import com.example.core.PositionList;
import java.io.IOException;

public class PositionSerializationTest {

    @Test
    public void testSinglePositionSerialization() throws IOException {
        // Create a single position
        Position original = new Position(
                193, // documentId
                1, // sentenceId
                10, // beginPosition
                15 // endPosition
        );

        // Create PositionList with single position
        PositionList originalList = new PositionList();
        originalList.add(original);

        // Serialize and deserialize
        byte[] serialized = originalList.serialize();
        PositionList deserializedList = PositionList.deserialize(serialized);

        // Verify
        List<Position> positions = deserializedList.getPositions();
        assertEquals(1, positions.size(), "Should have exactly one position");

        Position deserialized = positions.get(0);
        assertEquals(original.getDocumentId(), deserialized.getDocumentId(), "Document ID mismatch");
        assertEquals(original.getSentenceId(), deserialized.getSentenceId(), "Sentence ID mismatch");
        assertEquals(original.getBeginPosition(), deserialized.getBeginPosition(), "Begin position mismatch");
        assertEquals(original.getEndPosition(), deserialized.getEndPosition(), "End position mismatch");
    }

    @Test
    public void testMultiplePositionsSerialization() throws IOException {
        // Create test data
        PositionList originalList = new PositionList();
        originalList.add(new Position(193, 1, 1, 3));
        originalList.add(new Position(222, 3, 3, 5));
        originalList.add(new Position(3013, 1, 1, 3));
        originalList.add(new Position(9999, 15, 15, 18));

        // Serialize
        byte[] serialized = originalList.serialize();

        // Deserialize
        PositionList deserializedList = PositionList.deserialize(serialized);

        // Verify
        List<Position> original = originalList.getPositions();
        List<Position> deserialized = deserializedList.getPositions();

        assertEquals(original.size(), deserialized.size(), "Size mismatch");

        for (int i = 0; i < original.size(); i++) {
            Position op = original.get(i);
            Position dp = deserialized.get(i);

            assertEquals(op.getDocumentId(), dp.getDocumentId(),
                    "Document ID mismatch at index " + i);
            assertEquals(op.getSentenceId(), dp.getSentenceId(),
                    "Sentence ID mismatch at index " + i);
            assertEquals(op.getBeginPosition(), dp.getBeginPosition(),
                    "Begin position mismatch at index " + i);
            assertEquals(op.getEndPosition(), dp.getEndPosition(),
                    "End position mismatch at index " + i);
        }
    }

    @Test
    public void testEmptyListSerialization() throws IOException {
        PositionList emptyList = new PositionList();
        byte[] serialized = emptyList.serialize();
        PositionList deserialized = PositionList.deserialize(serialized);

        assertEquals(0, deserialized.size(), "Deserialized empty list should have size 0");
    }

    @Test
    public void testLargeListSerialization() throws IOException {
        PositionList list = new PositionList();
        int numPositions = 10000;
        
        // Create positions with realistic document IDs
        for (int i = 0; i < numPositions; i++) {
            // Use document ID pattern: 1000 + (i / 100) to simulate realistic docs
            int docId = 1000 + (i / 100);  // Creates 100 positions per document
            int sentId = i % 20;           // 20 sentences per document
            list.add(new Position(
                docId,
                sentId,
                i * 5,
                i * 5 + 4
            ));
        }

        // Serialize and deserialize
        byte[] serialized = list.serialize();
        PositionList deserialized = PositionList.deserialize(serialized);

        // Verify
        assertEquals(numPositions, deserialized.size(),
                "Should maintain size after serialization/deserialization");

        // Check a few random positions
        List<Position> originalPositions = list.getPositions();
        List<Position> deserializedPositions = deserialized.getPositions();

        for (int i : Arrays.asList(0, 999, 5000, 9999)) {
            Position op = originalPositions.get(i);
            Position dp = deserializedPositions.get(i);
            assertEquals(op.getDocumentId(), dp.getDocumentId(),
                    "Document ID mismatch at index " + i);
        }
    }

    @Test
    public void testPositionSorting() {
        PositionList list = new PositionList();

        // Add positions in random order
        list.add(new Position(2, 1, 5, 8));
        list.add(new Position(1, 2, 3, 6));
        list.add(new Position(1, 1, 7, 9));
        list.add(new Position(1, 1, 1, 4));

        // Sort
        list.sort();

        // Verify order
        List<Position> sorted = list.getPositions();
        assertEquals(1, sorted.get(0).getDocumentId(), "First position should be doc 1");
        assertEquals(1, sorted.get(0).getBeginPosition(), "Should be earliest position in doc 1");
        assertEquals(2, sorted.get(3).getDocumentId(), "Last position should be doc 2");
    }
}
