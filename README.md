# SimpleFileServer

A lightweight HTTP file server in Java with optional zero-copy support.

## 1. Features

- Serves files over HTTP.
- Supports high-concurrency using virtual threads (Java 25+).
- Optional zero-copy file transfer using `FileChannel.transferTo` when possible.
- Simple and self-contained; no external dependencies.

## 2. Requirements

- Java 25+
- Maven 3.8+
- Optional: GraalVM for native image compilation

## 3. Compilation

### 3.1 JVM (Non-Native) Jar

```bash
# Build the jar
mvn clean package
```
* Run:
```bash
java -jar target/SimpleFileServer.jar
```

### 3.2 Native Compilation (Optional)
```bash
# Build native image using GraalVM
export JAVA_HOME={GraalVM HOME}
mvn clean package -Pnative
```
* Run:
```bash
./target/SimpleFileServer
```

## 4. Usage
By default, serves HTTP on 0.0.0.0:8080.

Place files in the working directory or configure file paths in the server code.
