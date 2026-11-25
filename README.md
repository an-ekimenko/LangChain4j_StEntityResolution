# LangChain4j_StEntityResolution

**An enterprise-grade, deterministic-guarded AI integration service for automated Master Data Management (MDM) entity resolution, powered by Java 17, LangChain4j, local LLM inference (Ollama), and Google Guice 7.**

The service resolves and normalizes unstandardized units of measurement (`st_Name`) coming from volatile 1C ERP staging layers into canonical MDM standards for **SAP BW/4HANA** analytics, utilizing incremental dictionary caching, structured JSON outputs (`qwen2.5:7b`), and a non-destructive delta-override pattern.

---

## 🎯 Business Context & Data Flow Architecture

### The Problem
Legacy ERP systems (1C) periodically overwrite staging tables (`GoodsWholesale` and `SpaceType`) every few hours, wiping out any manual or script-based fixes. Meanwhile, unstandardized text variants (`"кг"`, `"кг."`, `"шт."`, `"штука"`, `"канистра 4,5 л"`, `"канистра 4.5л"`) fragment analytical reporting in **SAP BW/4HANA**.

### The Solution: Non-Destructive Enrichment Layer
Instead of mutating volatile staging tables, this service maintains a lightweight **exception-only override table** (`GoodsWholesale_AISpaceType`) backed by a persistent **AI knowledge cache** (`MDM_Unit_Dictionary`):

```text
[1C ERP (3h Full Overwrite)]
       │
       ▼
[Staging Views: GoodsWholesale_CHNG_DirtyST / GoodsWholesale_DirtyST]
       │
       ├──► (1) Extract DISTINCT st_Name ──► Filter out known keys from [MDM_Unit_Dictionary]
       │                                            │
       │                                     [Only New Variants]
       │                                            ▼
       │                             [LangChain4j + Ollama (qwen2.5:7b)]
       │                                            │ (Structured JSON)
       │                                            ▼
       │                                   [NumericGuardrail] ──► Splits distinct numbers (175 кг != 176 кг)
       │                                            │
       │                                            ▼
       │                             [Save Rules to MDM_Unit_Dictionary]
       │
       └──► (2) Delta Lookup (LEFT JOIN) ──► Identify rows where st_Name != CanonicalName
                                                    │
                                                    ▼
                                     [GoodsWholesale_AISpaceType] (Stores ONLY exceptions: UIDDocument, Line, SpaceType, DateStart)
                                                    │
                                                    ▼
[Final Views: GoodsWholesale / GoodsWholesale_CHNG] (LEFT JOIN + ISNULL(st.SpaceType, ws.st_Name))
       │
       ▼
[SAP BW/4HANA DTP Processes]
```

### Core Storage Entities
1. **`MDM_Unit_Dictionary` (`DirtyName` PK -> `CanonicalName`):**
   * Stores unique string-level normalization rules.
   * Ensures the LLM is invoked **only once** per unseen dirty string, minimizing CPU/GPU inference overhead.
2. **`GoodsWholesale_AISpaceType` (`UIDDocument`, `Line` PK -> `SpaceType`, `DateStart`):**
   * Stores document-line overrides **exclusively for records where the original unit differs from the canonical standard**.
   * `DateStart` preserves the source document timestamp to enable fast index-based cleanup triggers when records are deleted from the upstream staging table.

---

## 🛡️ Key Engineering Features

* **Incremental Dictionary Caching:** Deduplicates incoming unit names against `MDM_Unit_Dictionary` before invoking LLM inference.
* **Structured AI Outputs (`AiServices`):** Uses LangChain4j declarative interfaces with `temperature = 0.0` and strict JSON Schema enforcement (`Capability.RESPONSE_FORMAT_JSON_SCHEMA`) on local inference (`qwen2.5:7b`).
* **Deterministic Numeric Guardrails (`NumericGuardrail`):** Audits LLM clusters via regex signature extraction (`\d+(?:[.,]\d+)?`) to automatically split hallucinated groupings (e.g., isolating `"бочка 175 кг"` from `"бочка 176 кг"`).
* **Idempotent Delta Persistence & Resilience:** Executes a `LEFT JOIN` delta check prior to persistence, utilizing `HibernateBatchExecutor` with monotonic-clock (`System.nanoTime()`) exponential backoff and jitter.
* **Shared-Dependency Deployment Topology:** Excludes third-party JARs from the main artifact, routing classpath dependencies to `../dependencies/` and emitting YAML execution receipts (`exchange/result.yaml`).

---

## 📂 Project Structure & Component Responsibilities

```text
LangChain4j_StEntityResolution/
├── config/
│   ├── properties.yaml.dist           # Template for source views, DB retry budgets, and Ollama parameters
│   └── Hibernate.cfg.xml.dist         # Template for MS SQL Server connection (HikariCP + P6Spy)
├── exchange/
│   └── result.yaml                    # Execution status receipt for external orchestrators (Airflow / Control-M)
├── src/main/
│   ├── java/by/beloil/ai/mdm/
│   │   ├── config/
│   │   │   ├── AppConfig.java         # Jackson-mapped configuration model (LLM, retry, and view settings)
│   │   │   └── AIProxyModule.java     # Google Guice 7 DI bindings (OllamaChatModel, AiServices, SessionFactory)
│   │   ├── hibernate/
│   │   │   └── HibernateBatchExecutor.java # Transactional session manager, batching, and Exponential Backoff
│   │   ├── model/
│   │   │   ├── UnitGroup.java         # Structured Output DTO representing a canonical unit and its variants
│   │   │   ├── UnitNormalizationResult.java # Root Structured Output wrapper for LangChain4j JSON Schema
│   │   │   ├── UnitDictionaryEntity.java    # JPA entity for [dwbeloil].[dbo].[MDM_Unit_Dictionary]
│   │   │   ├── GoodsSpaceTypeEntity.java    # JPA entity for [dwbeloil].[dbo].[GoodsWholesale_AISpaceType]
│   │   │   └── GoodsSpaceTypePK.java        # Composite primary key (1C binary(17) UID + int Line)
│   │   ├── repository/
│   │   │   └── MdmUnitRepository.java       # Read/delta SQL queries and mapping logic
│   │   ├── service/
│   │   │   ├── UnitNormalizerAgent.java     # LangChain4j @SystemMessage / @UserMessage declarative AI service
│   │   │   ├── NumericGuardrail.java        # Regex-based post-LLM guardrail preventing numeric collisions
│   │   │   └── EntityResolutionPipeline.java # Core 4-step orchestrator (Load -> Classify -> Guard -> Upsert)
│   │   └── MainLauncher.java          # CLI entry point, Guice bootstrap, and YAML receipt writer
│   └── resources/
│       ├── Log4j2.xml                 # Asynchronous/file logging configuration (app, Hibernate, P6Spy)
│       └── spy.properties             # P6Spy SQL interception filter (logs INSERT/UPDATE/MERGE mutations)
└── pom.xml                            # Maven build configuration with external ../dependencies/ packaging
```

---

## 🛠️ Tech Stack

* **Core & DI:** Java 17, Google Guice 7, Lombok
* **AI & Inference:** LangChain4j (`1.0.0-beta2`), Ollama (`qwen2.5:7b`, JSON Schema Structured Outputs)
* **Persistence & Pooling:** Hibernate ORM 6, HikariCP, MS SQL Server JDBC, P6Spy
* **Serialization & Logging:** Jackson YAML, Log4j2 / SLF4J

---

## 🚀 Setup & Execution

### 1. Prerequisites
* **Java 17+ (LTS)** & **Apache Maven 3.8+**
  * *Why Java 17:* Serves as the mandatory runtime baseline for **LangChain4j 1.x**, **Hibernate ORM 6**, and **Google Guice 7**. It provides native support for immutable `record` types (used for strict LLM JSON Schema generation), multi-line `Text Blocks` (for maintainable system prompts and SQL queries), and `Pattern Matching for instanceof`, while offering predictable G1GC memory management alongside memory-intensive local LLM inference.
* **Ollama** running locally or on an internal inference node with the target model pulled:
  ```bash
  ollama pull qwen2.5:7b
  ```

### 2. Configuration
Copy the `.dist` templates in `config/` and remove the `.dist` extension:
* `config/Hibernate.cfg.xml` — configure MS SQL Server host, credentials, and SSL parameters.
* `config/properties.yaml` — specify the source view (`GoodsWholesale_CHNG_DirtyST`), Ollama URL, `num_ctx`, and `batch_size`.

### 3. Build & Run
Package the application (dependencies are automatically copied to `../dependencies/` and the executable JAR is placed in the project root):
```bash
mvn clean package
java -jar LangChain4j_StEntityResolution.jar
```

To run a historical backfill against a full staging view, pass the view name as a CLI argument:
```bash
java -jar LangChain4j_StEntityResolution.jar "[KISjoinMDM].[dbo].[GoodsWholesale_DirtyST]"
```

### 4. Execution Receipt (`exchange/result.yaml`)
Upon completion, the worker emits a structured status report for job schedulers:
```yaml
status: "SUCCESS"
timestamp: "2026-11-25T16:30:00"
records_upserted: 142
duration_ms: 18450
```
