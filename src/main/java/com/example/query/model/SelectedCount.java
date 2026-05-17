package com.example.query.model;

/**
 * Represents a COUNT expression in the SELECT clause.
 * The specific counting logic is described by the sealed {@link CountSpec}
 * hierarchy.
 */
public record SelectedCount(CountSpec spec) implements SelectedColumn {

    /**
     * Sealed hierarchy of count specifications.
     */
    public sealed interface CountSpec permits CountAll, CountUnique, CountDocuments {
    }

    public record CountAll() implements CountSpec {
    }

    public record CountUnique(String qualifiedVariableName) implements CountSpec {
        public CountUnique {
            if (qualifiedVariableName == null || !qualifiedVariableName.contains(".")) {
                throw new IllegalArgumentException(
                        "CountUnique requires a qualified variable name (alias.var), got: "
                                + qualifiedVariableName);
            }
        }
    }

    public record CountDocuments() implements CountSpec {
    }

    public SelectedCount {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
    }

    @Override
    public String columnName() {
        return switch (spec) {
            case CountAll __ -> "COUNT(*)";
            case CountUnique(var v) -> "count_unique_" + v.replace('.', '_');
            case CountDocuments __ -> "document_count";
        };
    }

    @Override
    public String toString() {
        return switch (spec) {
            case CountAll __ -> "COUNT(*)";
            case CountUnique(var v) -> "COUNT(UNIQUE " + v + ")";
            case CountDocuments __ -> "COUNT(DOCUMENTS)";
        };
    }
}
