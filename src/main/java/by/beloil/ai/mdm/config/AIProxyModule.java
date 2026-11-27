package by.beloil.ai.mdm.config;

import by.beloil.ai.mdm.model.GoodsSpaceTypeEntity;
import by.beloil.ai.mdm.model.UnitDictionaryEntity;
import by.beloil.ai.mdm.service.NumericGuardrail;
import by.beloil.ai.mdm.service.UnitNormalizerAgent;
import by.beloil.ai.mdm.service.UnitVerifierAgent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.service.AiServices;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Google Guice dependency injection module that configures and provides singleton instances
 * for application settings, Hibernate persistence, Ollama LLM integration, and MDM domain services.
 */
public class AIProxyModule extends AbstractModule {

    /**
     * Loads and parses the external YAML configuration file ({@code config/properties.yaml})
     * into an {@link AppConfig} instance.
     *
     * @return populated application configuration object
     * @throws IOException if the configuration file cannot be read or parsed
     */
    @Provides
    @Singleton
    public AppConfig provideAppConfig() throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        return mapper.readValue(new File("config/properties.yaml"), AppConfig.class);
    }

    /**
     * Initializes the Hibernate {@link SessionFactory} from {@code config/Hibernate.cfg.xml}
     * and registers the MDM JPA entity classes.
     *
     * @return configured singleton Hibernate {@link SessionFactory}
     */
    @Provides
    @Singleton
    public SessionFactory provideSessionFactory() {
        return new Configuration()
                .configure(new File("config/Hibernate.cfg.xml"))
                .addAnnotatedClass(UnitDictionaryEntity.class)
                .addAnnotatedClass(GoodsSpaceTypeEntity.class)
                .buildSessionFactory();
    }

    /**
     * Builds and configures the local Ollama {@link ChatLanguageModel} client with strict
     * JSON schema structured output support based on application LLM settings.
     *
     * @param config application configuration containing LLM endpoint and model parameters
     * @return configured {@link ChatLanguageModel} instance
     */
    @Provides
    @Singleton
    public ChatLanguageModel provideLocalClassifierModel(AppConfig config) {
        AppConfig.LlmConfig llm = config.getSpaceType().getLlm();
        return OllamaChatModel.builder()
                .baseUrl(llm.getBaseUrl())
                .modelName(llm.getModelName())
                .temperature(llm.getTemperature())
                .numCtx(llm.getNumCtx())
                .timeout(Duration.ofSeconds(llm.getTimeoutSeconds()))
                .supportedCapabilities(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA))
                .maxRetries(llm.getMaxRetries())
                .build();
    }

    /**
     * Creates the LangChain4j declarative AI service proxy implementing {@link UnitNormalizerAgent}.
     *
     * @param model underlying chat language model used for inference
     * @return proxy instance of {@link UnitNormalizerAgent}
     */
    @Provides
    @Singleton
    public UnitNormalizerAgent provideUnitNormalizerAgent(ChatLanguageModel model) {
        return AiServices.builder(UnitNormalizerAgent.class)
                .chatLanguageModel(model)
                .build();
    }

    /**
     * Provides the post-processing numeric guardrail used to validate LLM-grouped units.
     *
     * @return singleton {@link NumericGuardrail} instance
     */
    @Provides
    @Singleton
    public NumericGuardrail provideNumericGuardrail() {
        return new NumericGuardrail();
    }
    
    /**
     * Creates the LangChain4j declarative AI service proxy implementing {@link UnitVerifierAgent}
     * used for secondary LLM-as-a-Judge semantic auditing of proposed unit mappings.
     *
     * @param model underlying chat language model used for verification inference
     * @return singleton proxy instance of {@link UnitVerifierAgent}
     */
    @Provides
    @Singleton
    public UnitVerifierAgent provideUnitVerifierAgent(ChatLanguageModel model) {
        return AiServices.builder(UnitVerifierAgent.class)
                .chatLanguageModel(model)
                .build();
    }

}


