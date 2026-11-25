package by.beloil.ai.mdm;

import by.beloil.ai.mdm.config.AIProxyModule;
import by.beloil.ai.mdm.config.AppConfig;
import by.beloil.ai.mdm.service.EntityResolutionPipeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.inject.Guice;
import com.google.inject.Injector;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Application entry point responsible for bootstrapping the Guice dependency injection container,
 * running the MDM {@link EntityResolutionPipeline}, managing Hibernate resource cleanup,
 * and writing a YAML execution receipt for external orchestrators.
 */
@Slf4j
public class MainLauncher {

    /**
     * Launches the unit of measurement entity resolution batch job.
     *
     * @param args optional command-line arguments; {@code args[0]} overrides the configured source view name
     */
    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();
        SessionFactory sessionFactory = null;
        int processedCount = 0;
        String status = "SUCCESS";
        String errorMessage = null;

        try {
            Injector injector = Guice.createInjector(new AIProxyModule());
            AppConfig config = injector.getInstance(AppConfig.class);
            sessionFactory = injector.getInstance(SessionFactory.class);
            EntityResolutionPipeline pipeline = injector.getInstance(EntityResolutionPipeline.class);

            // Allow overriding the source view name via the first CLI argument (e.g., for historical backfills)
            String targetView = (args.length > 0 && !args[0].isBlank())
                    ? args[0]
                    : config.getSpaceType().getSourceView();

            log.info("Starting LangChain4j_StEntityResolution for view: {}", targetView);
            processedCount = pipeline.execute(targetView);

        } catch (Exception e) {
            status = "FAILED";
            errorMessage = e.getMessage();
            log.error("Critical error during Entity Resolution execution", e);
        } finally {
            if (sessionFactory != null && !sessionFactory.isClosed()) {
                sessionFactory.close();
            }
            writeExecutionReceipt(status, processedCount, System.currentTimeMillis() - startTime, errorMessage);
        }

        if ("FAILED".equals(status)) {
            System.exit(1);
        }
    }

    /**
     * Writes a structured execution summary receipt to {@code exchange/result.yaml}
     * for downstream monitoring and ETL orchestration tools.
     *
     * @param status         execution outcome ({@code SUCCESS} or {@code FAILED})
     * @param recordsUpdated number of normalized records upserted into the target table
     * @param durationMs     total execution duration in milliseconds
     * @param error          error message if the run failed, or {@code null} on success
     */
    private static void writeExecutionReceipt(String status, int recordsUpdated, long durationMs, String error) {
        try {
            File exchangeDir = new File("exchange");
            if (!exchangeDir.exists()) {
                exchangeDir.mkdirs();
            }
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("status", status);
            receipt.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            receipt.put("records_upserted", recordsUpdated);
            receipt.put("duration_ms", durationMs);
            if (error != null) {
                receipt.put("error", error);
            }
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.writeValue(new File(exchangeDir, "result.yaml"), receipt);
        } catch (Exception e) {
            log.error("Failed to write execution receipt file exchange/result.yaml", e);
        }
    }
}
