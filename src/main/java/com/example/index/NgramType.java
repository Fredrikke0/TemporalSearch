package com.example.index;

public enum NgramType {
    UNIGRAM("unigram"),
    BIGRAM("bigram"),
    TRIGRAM("trigram");

    private final String indexName;

    NgramType(String indexName) {
        this.indexName = indexName;
    }

    public String getIndexName() {
        return indexName;
    }

    public String getTemporaryBySentenceIndexName() {
        return "temp_" + this.name().toLowerCase() + "s_by_sentence"; // e.g., temp_unigrams_by_sentence
    }
}