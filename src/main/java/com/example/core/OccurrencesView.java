package com.example.core;

/**
 * A non-allocating slice view over a cell's occurrences inside an
 * {@link OccurrencesBlock}.
 * All offsets are into the parent CSR's {@code begins} array.
 *
 * @param begins         reference to the parent OccurrencesBlock begins array
 * @param offset         start index within {@code begins} for this cell
 * @param length         number of begins entries for this cell
 * @param constantLength the fixed length in characters of the term
 */
public record OccurrencesView(byte[] begins, int offset, int length, byte constantLength) {

    /**
     * Returns the char-level begin offset of the k-th occurrence within this cell.
     *
     * @param k the occurrence index (0-based)
     * @return the unsigned byte value (0–255) as the begin character offset
     * @throws IndexOutOfBoundsException if k is out of range
     */
    public int begin(int k) {
        if (k < 0 || k >= length) {
            throw new IndexOutOfBoundsException(
                    "Occurrence index " + k + " out of [0, " + length + ")");
        }
        return Byte.toUnsignedInt(begins[offset + k]);
    }

    /**
     * Returns the number of occurrences in this cell.
     *
     * @return the occurrence count
     */
    public int size() {
        return length;
    }

    /**
     * Returns the constant length for this term (end = begin + constantLength).
     *
     * @return the constant length in characters
     */
    @Override
    public byte constantLength() {
        return constantLength;
    }
}
