# ChronoReason

A high-performance temporal reasoning system. It annotates documents, builds multiple RocksDB-backed indexes, and provides a query engine (ANTLR-based) for temporal-aware search over an large corpus.

## Overview and Main Components

- Data conversion (pre-requisite before anything else)

  - `src/main/java/com/example/WikiJsonToSqlite.java`: Converts Wikipedia CirrusSearch JSON dumps into an SQLite database with a `documents` table.
  - `src/main/java/com/example/NytXmlToSqlite.java`: Converts NYT Corpus `.tar.gz` archives (XML) into the same SQLite schema.

- Pipeline and indexing

  - `src/main/java/com/example/Pipeline.java`: Orchestrates annotation (Stanford CoreNLP) and index generation (unigram, bigram, trigram, dependency, NER, NER date, POS, nash, and stitch variants). Manages project directories and `--force` cleanup.

- Query engine (ANTLR-based)

  - `src/main/java/com/example/QueryCLI.java`: Entry point for executing queries, supporting interactive mode and single queries. Uses the ANTLR grammar in `src/main/antlr4/com/example/query/parser/QueryLang.g4`.

- Index browsing / debugging

  - `src/main/java/com/example/RocksDBBrowser.java`: Tool for inspecting RocksDB index contents (keys/values, prefixes, stats). Useful for debugging.

- Benchmarking and verification
  - `benchmark.py`: Benchmarks the query engine and indexes across strategy combinations (cold/warm cache modes).
  - `analyze_benchmarks.py`: Aggregates benchmark CSVs and renders a compact console and LaTeX table.
  - `verify_correctness.py`: Ensures different strategy combinations (temporal/pushdown/stitch) produce identical results.

## Install & Build

1. Ensure Java 21+ and Maven are installed.

2. Install the Nash dependency and build the project:

```bash
mvn install:install-file -Dfile=sandbox/lib/nash.jar -DgroupId=no.ntnu -DartifactId=nash -Dversion=1.0 -Dpackaging=jar
mvn clean package
```

This produces JARs in `target/`.

## Workflow

1. Convert data to SQLite

- Wikipedia JSON (CirrusSearch) → SQLite

```bash
mvn -q exec:java -Dexec.mainClass=com.example.WikiJsonToSqlite -- \
  -f /path/to/wiki.json \
  -d /path/to/data/wiki.db \
  -l 200000   # optional limit
```

- NYT Corpus `.tar.gz` → SQLite

```bash
mvn -q exec:java -Dexec.mainClass=com.example.NytXmlToSqlite -- \
  -i /path/to/nyt_archives_dir \
  -o /path/to/data/nyt.db \
  -l 100000   # optional global limit
```

2. Annotate and index

```bash
mvn -q exec:java -Dexec.mainClass=com.example.Pipeline -- \
  -s all \
  --db-file /path/to/data/nyt.db \
  --index-dir /path/to/projects_root \
  --force   # optional cleanup
```

Key options (subset):

- Stages: `-s`/`--stage` ∈ {`all`, `annotate`, `index`}
- Annotation: `-b`/`--batch-size`, `-t`/`--threads`, `-l`/`--limit`, `--fix-document-ids`, `--start-doc-id`
- Indexing: `--stopwords`, `--idx-batch-size`, `-y`/`--index-type` (`unigram`, `bigram`, `trigram`, `dependency`, `ner`, `ner_date`, `pos`, `nash`, stitch types like `stitch_unigram_ner`, or meta `stitches`/`all`), `--custom-temp-dir`, `--force`

The pipeline writes a project manifest; `QueryCLI` can auto-resolve the DB path from it.

3. Query the project

- Interactive mode:

```bash
mvn -q exec:java -Dexec.mainClass=com.example.QueryCLI -- \
  --index-root-dir /path/to/projects_root

# At the Query> prompt:
SET STRATEGY temporal=nash pushdown=optimized stitch=optimized
SET OUTPUT NONE
SELECT DOCUMENT_ID, SNIPPET FROM my_project
  WHERE CONTAINS('economy') AND DATE(= 2008)
  LIMIT 20;
```

- Single query:

```bash
mvn -q exec:java -Dexec.mainClass=com.example.QueryCLI -- \
  --index-root-dir /path/to/projects_root \
  --temporal-strategy nash \
  --pushdown-strategy optimized \
  --stitch-strategy optimized \
  "SELECT COUNT(*) FROM my_project WHERE POS(NN) AND NER(PERSON);"
```

Notes:

- Strategies: temporal ∈ {`naive`, `nash`}, pushdown ∈ {`none`, `optimized`}, stitch ∈ {`none`, `optimized`}.
- Grammar file: `src/main/antlr4/com/example/query/parser/QueryLang.g4`.
- `FROM` uses the project name (derived from DB filename) under `--index-root-dir`.

## Tools for Debugging & Evaluation

- RocksDB browser

```bash
mvn -q exec:java -Dexec.mainClass=com.example.RocksDBBrowser -- \
  --index-type summary \
  --db-path /path/to/projects_root/my_project
```

- Benchmarks and analysis

```bash
python3 benchmark.py --query-dir queries --index-root-dir /path/to/projects_root --output-dir bench_out
python3 analyze_benchmarks.py --file bench_out/1HOP/somefile_1hop_results.csv --table-title "1-hop Benchmark Performance"
python3 verify_correctness.py --query-dir queries --index-root-dir /path/to/projects_root --output-dir verify_out
```

## Troubleshooting

- Memory pressure during annotation/indexing:
  - Lower `--batch-size`, reduce `--threads`, or increase JVM heap (e.g., `-Xmx`)
- Slow indexing:
  - Increase batch sizes if memory allows; ensure fast storage; consider a custom temp dir on a fast disk
- Index inspection:
  - Use `RocksDBBrowser` with `--stats` or `--prefix`/`--key` to inspect specific entries
