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
* **`MDM_Unit_Dictionary`:** Caches normalization rules to minimize LLM inference calls.
* **`GoodsWholesale_AISpaceType`:** Manages record overrides and parses SAP BW dates into standard SQL datetime formats.

---

## 🛡️ Key Engineering Features

* **Incremental Dictionary Caching:** Deduplicates unit names prior to LLM processing.
* **Structured AI Outputs:** Employs LangChain4j with deterministic settings and strict JSON Schema enforcement on `qwen2.5:7b`.
* **Deterministic Guardrails:** Utilizes composite signatures to handle anomalies across numbers, dimensions, and packaging.
* **Idempotent Delta Persistence:** Executes delta checks via `HibernateBatchExecutor` with backoff and jitter.
* **Shared-Dependency Deployment Topology:** Manages classpath dependencies and generates YAML execution receipts.

---

## 📂 Project Structure & Component Responsibilities

The project is structured under `LangChain4j_StEntityResolution/` with configuration templates, exchange status files, core Java source code, and resources:

* **`config/`**: Contains `properties.yaml.dist` (source views, DB retry budgets, Ollama parameters) and `Hibernate.cfg.xml.dist` (MS SQL Server connection with HikariCP and P6Spy).
* **`exchange/`**: Features `result.yaml` for execution status receipts used by external orchestrators like Airflow or Control-M.
* **`src/main/java/by/beloil/ai/mdm/`**: 
  * `config/`: App configuration mapping (`AppConfig.java`) and Google Guice 7 dependency injection bindings (`AIProxyModule.java`).
  * `hibernate/`: Transactional session and batch management (`HibernateBatchExecutor.java`).
  * `model/`: Data models and enums including `DictionaryStatus`, `UnitGroup`, `UnitNormalizationResult`, `VerificationVerdict`, JPA entities (`UnitDictionaryEntity`, `GoodsSpaceTypeEntity`), and composite keys (`GoodsSpaceTypePK`).
  * `repository/`: SQL query execution and delta loading (`MdmUnitRepository.java`).
  * `service/`: Core business logic containing LangChain4j declarative AI services (`UnitNormalizerAgent.java`, `UnitVerifierAgent.java`), `NumericGuardrail.java`, `MappingValidationService.java`, and the 4-step `EntityResolutionPipeline.java`.
  * Root launcher: `MainLauncher.java` for CLI bootstrap and receipt generation.
* **`src/main/resources/`**: Logging (`Log4j2.xml`) and SQL interception (`spy.properties`).
* **`pom.xml`**: Maven build configuration.


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

## 🛠 Tech Stack

* **Core & DI:** Java 17, Google Guice 7, Lombok
* **AI & Inference:** LangChain4j (`1.0.0-beta2`), Ollama (`qwen2.5:7b`, JSON Schema Structured Outputs, LLM-as-a-Judge)
* **Validation & NLP Metrics:** Apache Commons Text (`JaroWinklerSimilarity`), Regex Composite Guardrails
* **Persistence & Pooling:** Hibernate ORM 6, HikariCP, MS SQL Server JDBC, P6Spy
* **Serialization & Logging:** Jackson YAML, Log4j2 / SLF4J

---

## 🚀 Setup & Execution

### 🛠️ Prerequisites
* **Java:** JDK 17 or higher *(required by LangChain4j 1.x, Google Guice 7, Hibernate 6+, immutable `record` schemas for structured LLM outputs, and LTS G1GC memory efficiency)*
* **AI Inference Engine:** Ollama running locally or on an internal node with the target model pulled (`ollama pull qwen2.5:7b`)
* **Database:** MS SQL Server
* **Build System:** Apache Maven 3.8+


### 2. Configuration
Copy the `.dist` templates in `config/` and remove the `.dist` extension:
* `config/Hibernate.cfg.xml` — configure MS SQL Server host, credentials, and SSL parameters.
* `config/properties.yaml` — specify the source view (`GoodsWholesale_CHNG_DirtyST`), Ollama URL, `num_ctx`, and `batch_size`.

### 3. Build & Run

1. **Start the local inference engine and ensure the model is loaded:**
   ```bash
   ollama serve
   ollama run qwen2.5:7b
   ```
   *(If Ollama is already running as a background service, ensure the model is pulled via `ollama pull qwen2.5:7b` before starting the worker).*

2. **Package and execute the application** *(dependencies are automatically copied to `../dependencies/` and the executable JAR is placed in the project root)*:
   ```bash
   mvn clean package
   java -jar LangChain4j_StEntityResolution.jar
   ```

3. **Historical Backfill Mode (`fsv`):**
   To run normalization against the full historical staging view (`full_source_view` defined in `properties.yaml`), pass the `fsv` (*Full Staging View*) CLI argument:
   ```bash
   java -jar LangChain4j_StEntityResolution.jar fsv
   ```

### 4. Execution Receipt (`exchange/result.yaml`)
Upon completion, the worker emits a structured status report for job schedulers:
```yaml
status: "SUCCESS"
timestamp: "2026-11-25T16:30:00"
records_upserted: 142
duration_ms: 18450
```
