package com.example.index;

public enum AnnotationTypeSource {
    NER_DATE("ner_date", "date"), // indexName, identifierForTempAndOutput
    NER("ner", "ner"),
    POS("pos", "pos");

    private final String sourceIndexName;
    private final String typeIdentifier; // Used for naming temp/output stitch indexes

    AnnotationTypeSource(String sourceIndexName, String typeIdentifier) {
        this.sourceIndexName = sourceIndexName;
        this.typeIdentifier = typeIdentifier;
    }

    public String getSourceIndexName() {
        return sourceIndexName;
    }

    public String getTypeIdentifier() {
        return typeIdentifier;
    }

    public String getTemporaryBySentenceIndexName() {
        return "temp_" + this.typeIdentifier + "_by_sentence"; // e.g., temp_date_by_sentence
    }
}