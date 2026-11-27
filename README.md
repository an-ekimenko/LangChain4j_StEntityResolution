# LangChain4j_StEntityResolution

**An enterprise-grade, deterministic-guarded AI integration service for automated Master Data Management (MDM) entity resolution, powered by Java 17, LangChain4j, local LLM inference (Ollama), and Google Guice 7.**

The service resolves and normalizes unstandardized units of measurement (`st_Name`) coming from volatile 1C ERP staging layers into canonical MDM standards for **SAP BW/4HANA** analytics, utilizing incremental dictionary caching, structured JSON outputs (`qwen2.5:7b`), and a non-destructive delta-override pattern.

---

## 🎯 Business Context & Data Flow Architecture

### The Problem
Legacy ERP systems (1C) periodically overwrite staging tables (`GoodsWholesale` and `SpaceType`) every few hours, wiping out any manual or script-based fixes. Meanwhile, unstandardized text variants (`"кг"`, `"кг."`, `"шт."`, `"штука"`, `"канистра 4,5 л"`, `"канистра 4.5л"`) fragment analytical reporting in **SAP BW/4HANA**.

### The Solution: Non-Destructive Enrichment Layer

Instead of mutating volatile staging tables, this service maintains a lightweight **exception-only override table** (`GoodsWholesale_AISpaceType`) backed by a moderated **AI knowledge cache** (`MDM_Unit_Dictionary`):

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
       │                                   [NumericGuardrail] ──► Splits distinct numbers, dimensions & packaging
       │                                            │
       │                                            ▼
       │                             [MappingValidationService] ──► Jaro-Winkler + LLM-as-a-Judge Audit
       │                                     │              │
       │                          [AUTO_VERIFIED]    [NEEDS_REVIEW]
       │                                 │                  │
       │                                 ▼                  ▼
       │                      [Active Rules in MDM_Unit_Dictionary] (Quarantined until manual approval)
       │
       └──► (2) Delta Lookup (LEFT JOIN) ──► Identify rows where st_Name != Active CanonicalName
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

* **Incremental Dictionary Caching & Quarantine (`MDM_Unit_Dictionary`):** Deduplicates incoming unit names against all cached records prior to LLM inference, isolating unverified or disputed mappings in a `NEEDS_REVIEW` quarantine state without impacting downstream analytics.
* **Structured AI Outputs (`AiServices`):** Uses LangChain4j declarative interfaces with `temperature = 0.0` and strict JSON Schema enforcement (`Capability.RESPONSE_FORMAT_JSON_SCHEMA`) on local inference (`qwen2.5:7b`).
* **Deterministic Composite Guardrails (`NumericGuardrail`):** Audits LLM clusters via composite regex signatures (`[container_anchor]#[number:normalized_unit]`), preventing numeric collisions (`175 кг` ≠ `176 кг`), dimension mix-ups (`400 мл` ≠ `400 г`), and packaging loss (`канистра 5 л` ≠ `л`).
* **Multi-Stage Mapping Verification (`MappingValidationService`):** Combines lexical similarity metrics (`JaroWinklerSimilarity` via Apache Commons Text) with an isolated **LLM-as-a-Judge** verifier (`UnitVerifierAgent`) to audit non-trivial replacements before activation.
* **Idempotent Delta Persistence & Resilience:** Executes a `LEFT JOIN` delta check prior to persistence, utilizing `HibernateBatchExecutor` with monotonic-clock (`System.nanoTime()`) exponential backoff and jitter.
* **Shared-Dependency Deployment Topology:** Excludes third-party JARs from the main artifact, routing classpath dependencies to `../dependencies/` and emitting YAML execution receipts (`exchange/result.yaml`).


---

## 📂 Project Structure & Component Responsibilities

The project is structured under `LangChain4j_StEntityResolution/` with configuration templates, exchange status files, core Java source code, and resources:

* **`config/`**: Contains `properties.yaml.dist` (incremental/full source views, DB retry budgets, Ollama parameters) and `Hibernate.cfg.xml.dist` (MS SQL Server connection with HikariCP and P6Spy).
* **`exchange/`**: Features `result.yaml` for execution status receipts used by external orchestrators (Airflow / Control-M).
* **`src/main/java/by/beloil/ai/mdm/`**: 
  * `config/`: Jackson-mapped configuration model (`AppConfig.java`) and Google Guice 7 dependency injection bindings (`AIProxyModule.java`).
  * `hibernate/`: Transactional session management, JDBC batching, and monotonic-clock exponential backoff (`HibernateBatchExecutor.java`).
  * `model/`: Domain models and enums including `DictionaryStatus` (quarantine/approval lifecycle), `UnitGroup`, `UnitNormalizationResult`, `VerificationVerdict`, JPA entities (`UnitDictionaryEntity`, `GoodsSpaceTypeEntity`), and composite keys (`GoodsSpaceTypePK`).
  * `repository/`: SQL delta lookup (`LEFT JOIN`), dictionary loading, and SAP BW `YYYYMMDD` date parsing (`MdmUnitRepository.java`).
  * `service/`: Core business logic containing LangChain4j declarative AI services (`UnitNormalizerAgent.java`, `UnitVerifierAgent.java`), composite regex guardrails (`NumericGuardrail.java`), multi-stage validation (`MappingValidationService.java`), and the 4-step orchestrator (`EntityResolutionPipeline.java`).
  * Root launcher: `MainLauncher.java` for CLI bootstrap (`fsv` flag support), explicit JVM termination, and YAML receipt generation.
* **`src/main/resources/`**: Log4j2 configuration (`Log4j2.xml`) and P6Spy SQL mutation filter (`spy.properties`).
* **`pom.xml`**: Maven build configuration with external `../dependencies/` runtime packaging.
```
LangChain4j_StEntityResolution/
├── config/                   # External configuration files (properties.yaml, Hibernate MS SQL)
├── exchange/                 # Execution status receipts for external schedulers (result.yaml)
├── src/main/
│   ├── java/by/beloil/ai/mdm/
│   │   ├── config/           # Configuration models and DI bindings (Google Guice)
│   │   ├── hibernate/        # Session manager, batching, and Exponential Backoff retries
│   │   ├── model/            # JPA entities, enums, and DTOs (including LangChain4j JSON schemas)
│   │   ├── repository/       # Native SQL delta queries and SAP BW date parsing
│   │   ├── service/          # AI agents, entity resolution pipeline, guardrails, and LLM-as-a-Judge
│   │   └── MainLauncher.java # CLI application entry point
│   └── resources/            # Logging and SQL telemetry configurations (Log4j2, P6Spy)
└── pom.xml                   # Maven build descriptor
```

---

## 🛠 Tech Stack

* **Core & DI:** Java 17+ *(required by LangChain4j 1.x, Google Guice 7, Hibernate 6+, immutable `record` schemas for structured LLM outputs, and LTS G1GC memory efficiency)*, Google Guice 7, Lombok
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
