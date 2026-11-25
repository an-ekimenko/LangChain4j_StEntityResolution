package by.beloil.ai.mdm.service;

import by.beloil.ai.mdm.config.AppConfig;
import by.beloil.ai.mdm.model.GoodsSpaceTypeEntity;
import by.beloil.ai.mdm.model.UnitDictionaryEntity;
import by.beloil.ai.mdm.model.UnitGroup;
import by.beloil.ai.mdm.model.UnitNormalizationResult;
import by.beloil.ai.mdm.repository.MdmUnitRepository;
import com.google.inject.Inject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates the Master Data Management (MDM) entity resolution pipeline for units of measurement.
 * <p>
 * The pipeline performs the following steps:
 * <ol>
 *     <li>Loads the existing normalization dictionary from {@code [dwbeloil].[dbo].[MDM_Unit_Dictionary]}.</li>
 *     <li>Fetches distinct raw unit spellings from the source view and filters out already mapped variants.</li>
 *     <li>Classifies new variants in configured batch sizes using a local LLM and validates them with a numeric guardrail.</li>
 *     <li>Persists newly discovered mapping rules back to the MDM dictionary.</li>
 *     <li>Computes the delta of wholesale goods records requiring unit substitution and upserts them into {@code dbo.GoodsWholesale_AISpaceType}.</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class EntityResolutionPipeline {

    private final MdmUnitRepository repository;
    private final UnitNormalizerAgent agent;
    private final NumericGuardrail guardrail;
    private final AppConfig appConfig;

    /**
     * Executes the end-to-end unit normalization and entity resolution workflow against the given source view.
     *
     * @param sourceViewName name of the database view containing raw wholesale goods records
     * @return number of normalized records persisted to {@code dbo.GoodsWholesale_AISpaceType}
     */
    public int execute(String sourceViewName) {
        // 1. Load the accumulated dictionary from [dwbeloil].[dbo].[MDM_Unit_Dictionary]
        Map<String, String> dictionary = repository.loadDictionary();
        Set<String> existingCanonicals = dictionary.values().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));

        // 2. Fetch distinct st_Name values from the view and filter only new variants (trimmed)
        List<String> distinctRawUnits = repository.fetchDistinctDirtyUnits(sourceViewName);
        List<String> newTrimmedUnits = distinctRawUnits.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .filter(trimmed -> !dictionary.containsKey(trimmed))
                .sorted()
                .toList();

        log.info("Total unique UoMs in view: {}. New variants (missing from dictionary) for LLM: {}",
                distinctRawUnits.size(), newTrimmedUnits.size());

        // 3. Classify new variants in batches via the local LLM
        if (!newTrimmedUnits.isEmpty()) {
            int batchSize = Math.max(1, appConfig.getSpaceType().getLlm().getBatchSize());
            int totalBatches = (int) Math.ceil((double) newTrimmedUnits.size() / batchSize);
            List<UnitDictionaryEntity> newDictEntries = new ArrayList<>();

            for (int i = 0; i < newTrimmedUnits.size(); i += batchSize) {
                int currentBatchNum = (i / batchSize) + 1;
                List<String> batch = newTrimmedUnits.subList(i, Math.min(i + batchSize, newTrimmedUnits.size()));

                log.info("Sending batch {}/{} to LLM (size: {}): {}",
                        currentBatchNum, totalBatches, batch.size(), batch);

                UnitNormalizationResult llmResult = agent.normalizeBatch(existingCanonicals, batch);
                if (llmResult == null || llmResult.groups() == null) {
                    log.warn("LLM returned an empty result for batch {}/{}", currentBatchNum, totalBatches);
                    continue;
                }

                List<UnitGroup> verifiedGroups = guardrail.validateAndSplit(llmResult.groups());

                for (UnitGroup group : verifiedGroups) {
                    if (group.canonicalName() == null || group.variants() == null) {
                        continue;
                    }
                    String canonical = group.canonicalName().trim();
                    if (canonical.isEmpty()) {
                        continue;
                    }
                    existingCanonicals.add(canonical);

                    for (String variant : group.variants()) {
                        if (variant == null) {
                            continue;
                        }
                        String cleanVariant = variant.trim();
                        if (!cleanVariant.isEmpty() && !dictionary.containsKey(cleanVariant)) {
                            dictionary.put(cleanVariant, canonical);
                            newDictEntries.add(new UnitDictionaryEntity(cleanVariant, canonical));
                        }
                    }
                }
            }

            if (!newDictEntries.isEmpty()) {
                repository.saveNewDictionaryEntries(newDictEntries);
                log.info("Added {} new rules to the MDM_Unit_Dictionary table.", newDictEntries.size());
            }
        }

        // 4. Second pass: select only the delta of records (UIDDocument, Line, DateStart) requiring substitution
        List<GoodsSpaceTypeEntity> recordsToFix = repository.fetchRecordsRequiringNormalization(sourceViewName, dictionary);
        log.info("Found {} records requiring normalization for GoodsWholesale_AISpaceType.", recordsToFix.size());

        if (!recordsToFix.isEmpty()) {
            repository.saveNormalizedRecordsBatch(recordsToFix);
        }

        return recordsToFix.size();
    }
}
