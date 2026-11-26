package by.beloil.ai.mdm.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Root application configuration model mapped from the external configuration file (e.g., YAML or JSON).
 * Unknown properties are ignored during deserialization to ensure forward compatibility.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {

    /**
     * Configuration section for the wholesale goods space type (unit of measurement) normalization module.
     */
    @JsonProperty("space_type")
    private SpaceTypeConfig spaceType;

    /**
     * Settings governing the space type entity resolution pipeline, database retries, and LLM integration.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SpaceTypeConfig {

       /**
        * Default incremental MS SQL source view (monthly delta) containing raw wholesale goods records.
        */
       @JsonProperty("source_view")
       private String sourceView;

       /**
        * Full historical staging view containing all wholesale goods records 
        * (triggered via the {@code fsv} CLI flag).
        */
       @JsonProperty("full_source_view")
       private String fullSourceView;

        /**
         * Database resilience and retry policy parameters.
         */
        @JsonProperty("db_retry")
        private DbRetryConfig dbRetry = new DbRetryConfig();

        /**
         * Large Language Model (LLM) connection and inference settings.
         */
        @JsonProperty("llm")
        private LlmConfig llm = new LlmConfig();
    }

    /**
     * Configuration parameters for the Exponential Backoff database retry mechanism.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DbRetryConfig {

        /**
         * Maximum cumulative time budget (in milliseconds) allowed for retrying a failed database operation.
         * Defaults to 300,000 ms (5 minutes).
         */
        @JsonProperty("max_timeout_ms")
        private long maxTimeoutMs = 300_000L;

        /**
         * Initial backoff delay (in milliseconds) before the first retry attempt.
         * Defaults to 10,000 ms (10 seconds).
         */
        @JsonProperty("initial_timeout_ms")
        private long initialTimeoutMs = 10_000L;
    }

    /**
     * Configuration parameters for the Ollama / LangChain4j LLM client.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmConfig {

        /**
         * Base URL of the local or remote Ollama inference server.
         */
        @JsonProperty("base_url")
        private String baseUrl = "http://localhost:11434";

        /**
         * Identifier of the LLM model used for unit normalization (e.g., {@code qwen2.5:7b}).
         */
        @JsonProperty("model_name")
        private String modelName = "qwen2.5:7b";

        /**
         * Sampling temperature controlling output determinism (0.0 for maximally deterministic responses).
         */
        @JsonProperty("temperature")
        private double temperature = 0.0;

        /**
         * Context window size (in tokens) allocated for the model prompt and response.
         */
        @JsonProperty("num_ctx")
        private int numCtx = 2048;

        /**
         * Number of raw unit variants sent to the LLM in a single inference batch.
         */
        @JsonProperty("batch_size")
        private int batchSize = 20;

        /**
         * HTTP read/request timeout (in seconds) when waiting for a response from the LLM server.
         */
        @JsonProperty("timeout_seconds")
        private int timeoutSeconds = 180;

        /**
         * Maximum number of retry attempts on transient LLM API or structured parsing failures.
         */
        @JsonProperty("max_retries")
        private int maxRetries = 3;
    }
}

