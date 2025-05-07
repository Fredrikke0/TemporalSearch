# Java NLP Project

Java-based NLP pipeline and query engine.

## Build Instructions

### Main Application & Tests

Builds the main application (from root `src/main/java`) and runs tests (from root `src/test/java`):

```bash
mvn clean package
```

This creates executable JARs (e.g., `query-cli.jar`, `nlp-pipeline.jar`) in `target/`.

### Converters Module

The `converters` module (e.g., `NytXmlToSqlite`, `WikiJsonToSqlite`) is built separately.

From the project root:

```bash
mvn clean package -pl converters -am
```

Or, from the module directory:

```bash
cd converters
mvn clean package
```

This creates converter JARs in `converters/target/`.

**(Future):** Once modularization is complete, the root project will be a POM (`<packaging>pom</packaging>`), and `mvn clean package` will build all included core modules.
