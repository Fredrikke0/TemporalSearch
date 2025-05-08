# Temporal Search

A high-performance natural language processing pipeline specializing in temporal reasoning. The system performs text annotation and indexing with a focus on temporal information extraction, enabling advanced temporal-aware search capabilities.

## Features

- **Text Annotation**:
  - Uses Stanford CoreNLP for text analysis
- **Advanced Indexing**:
  - Multiple index types (unigram, bigram, trigram, dependency, NER date)
  - Temporal-aware index structures
  - Flexible storage using SQLite for documents and LevelDB for indexes
- **Temporal Query Interface**:
  - TBD

## Installation

1. Clone the repository:

   ```bash
   git clone https://github.com/yourusername/java-nlp.git
   cd java-nlp
   ```

2. Build the project:
   ```bash
   mvn clean package
   ```

This will create an executable JAR file in the `target` directory.

## Usage

The pipeline consists of three main components:

1. **Annotation**: Processes text using Stanford CoreNLP, with special focus on temporal information
2. **Indexing**: Generates multiple types of indexes including temporal-specific indexes
3. **Query Interface**: ...

### Basic Usage

Run all processing stages (annotation and indexing):

```bash
java -jar target/java-nlp-1.0-SNAPSHOT-jar-with-dependencies.jar \
    --stage all \
    --db data.db \
    --index-dir indexes
```

### Stage-Specific Usage

Run only the annotation stage:

```bash
java -jar target/java-nlp-1.0-SNAPSHOT-jar-with-dependencies.jar \
    --stage annotate \
    --db data.db \
    --batch_size 1000 \
    --threads 8
```

Run only the indexing stage:

```bash
java -jar target/java-nlp-1.0-SNAPSHOT-jar-with-dependencies.jar \
    --stage index \
    --db data.db \
    --index-dir indexes \
    --index-type all
```

### Command-Line Arguments

Common Arguments:

- `--stage`: Pipeline stage to run (`all`, `annotate`, `index`, or `analyze`)
- `--db`: SQLite database file path (required for annotation and indexing)
- `--batch_size`: Batch size for processing (default: 1000)

Annotation-Specific:

- `--threads`: Number of CoreNLP threads (default: 8)
- `--limit`: Limit the number of documents to process

Indexing-Specific:

- `--index-dir`: Directory for storing indexes (default: 'indexes')
- `--stopwords`: Path to stopwords file (default: stopwords.txt)
- `--index-type`: Type of index to generate (`unigram`, `bigram`, `trigram`, `dependency`, `ner_date`, or `all`)

## Project Status

- ✅ Document annotation pipeline
- ✅ Multi-strategy indexing
- 🚧 Temporal query interface (in design phase)
- 🚧 Advanced temporal reasoning capabilities

## Troubleshooting

1. Out of Memory Errors:

   - Reduce batch size
   - Reduce number of threads
   - Ensure sufficient heap space with `-Xmx` JVM argument

2. Slow Processing:

   - Increase batch size if memory allows
   - Increase number of threads if CPU allows
   - Check disk I/O performance

3. Index Errors:
   - Check log files for specific error messages
   - Run analysis stage to identify error patterns
   - Verify input data quality
