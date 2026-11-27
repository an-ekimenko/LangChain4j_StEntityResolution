package by.beloil.ai.mdm.service;

import by.beloil.ai.mdm.config.AppConfig;
import by.beloil.ai.mdm.model.*;
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
 *     <li>Loads all dictionary entries from {@code [dwbeloil].[dbo].[MDM_Unit_Dictionary]}, separating
 *         previously processed keys (to avoid redundant LLM calls) from active verified rules.</li>
 *     <li>Fetches distinct raw unit spellings from the source view and isolates unseen variants.</li>
 *     <li>Classifies new variants in batches via an LLM agent, enforces structural constraints via
 *         {@link NumericGuardrail}, and runs multi-stage validation ({@link MappingValidationService})
 *         with quarantine routing for suspicious mappings.</li>
 *     <li>Persists newly evaluated rules (both {@code AUTO_VERIFIED} and {@code NEEDS_REVIEW}) to the dictionary.</li>
 *     <li>Computes the delta of wholesale goods records requiring substitution using only active rules
 *         and batch-upserts them into {@code dbo.GoodsWholesale_AISpaceType}.</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class EntityResolutionPipeline {

    private final MdmUnitRepository repository;
    private final UnitNormalizerAgent agent;
    private final NumericGuardrail guardrail;
    private final MappingValidationService validationService;
    private final AppConfig appConfig;

    /**
     * Executes the end-to-end unit normalization and entity resolution workflow against the specified source view.
     *
     * @param sourceViewName name of the database view containing raw wholesale goods records
     * @return number of normalized records persisted to {@code dbo.GoodsWholesale_AISpaceType}
     */
    public int execute(String sourceViewName) {
        // 1. Load all dictionary entries from [dwbeloil].[dbo].[MDM_Unit_Dictionary]
        List<UnitDictionaryEntity> allEntries = repository.loadAllDictionaryEntries();

        // All known raw keys (including NEEDS_REVIEW and REJECTED) to prevent re-sending them to the LLM
        Set<String> allKnownDirtyKeys = allEntries.stream()
                .map(UnitDictionaryEntity::getDirtyName)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));

        // Only approved/auto-verified rules used for actual substitution in GoodsWholesale_AISpaceType
        Map<String, String> activeDictionary = allEntries.stream()
                .filter(e -> e.getStatus() == DictionaryStatus.AUTO_VERIFIED || e.getStatus() == DictionaryStatus.APPROVED)
                .filter(e -> e.getDirtyName() != null && e.getCanonicalName() != null)
                .collect(Collectors.toMap(
                        UnitDictionaryEntity::getDirtyName,
                        UnitDictionaryEntity::getCanonicalName,
                        (existing, replacement) -> existing
                ));

        // Set of already approved canonical designations passed into the LLM context
        Set<String> existingCanonicals = activeDictionary.values().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));

        // 2. Fetch distinct st_Name values from the view and filter only completely unseen variants
        List<String> distinctRawUnits = repository.fetchDistinctDirtyUnits(sourceViewName);
        List<String> newTrimmedUnits = distinctRawUnits.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .filter(trimmed -> !allKnownDirtyKeys.contains(trimmed))
                .sorted()
                .toList();

        log.info("Total unique UoMs in view: {}. New variants (missing from dictionary) for LLM: {}",
                distinctRawUnits.size(), newTrimmedUnits.size());

        // 3. Classify new variants in batches, pass through the guardrail, and run multi-stage validation
        if (!newTrimmedUnits.isEmpty()) {
            int batchSize = Math.max(1, appConfig.getSpaceType().getLlm().getBatchSize());
            int totalBatches = (int) Math.ceil((double) newTrimmedUnits.size() / batchSize);

            for (int i = 0; i < newTrimmedUnits.size(); i += batchSize) {
                int currentBatchNum = (i / batchSize) + 1;
                List<String> batch = newTrimmedUnits.subList(i, Math.min(i + batchSize, newTrimmedUnits.size()));
                List<UnitDictionaryEntity> batchEntries = new ArrayList<>();

                log.info("Sending batch {}/{} to LLM (size: {}): {}",
                        currentBatchNum, totalBatches, batch.size(), batch);

                Set<String> relevantCanonicals = existingCanonicals.stream()
                        .filter(c -> c.length() <= 8 || batch.stream().anyMatch(b ->
                                b.split("\\s+")[0].equalsIgnoreCase(c.split("\\s+")[0])))
                        .collect(Collectors.toCollection(TreeSet::new));


                UnitNormalizationResult llmResult = agent.normalizeBatch(relevantCanonicals, batch);

                if (llmResult == null || llmResult.groups() == null) {
                    log.warn("LLM returned an empty result for batch {}/{}", currentBatchNum, totalBatches);
                    continue;
                }

                // Enforce strict structural rules (numbers, dimensions, container anchors)
                List<UnitGroup> verifiedGroups = guardrail.validateAndSplit(llmResult.groups());

                for (UnitGroup group : verifiedGroups) {
                    if (group.canonicalName() == null || group.variants() == null) continue;
                    String canonical = group.canonicalName().trim();
                    if (canonical.isEmpty()) continue;

                    boolean isKnownCanonical = existingCanonicals.contains(canonical);

                    for (String variant : group.variants()) {
                        if (variant == null) continue;
                        String cleanVariant = variant.trim();
                        if (!cleanVariant.isEmpty() && !allKnownDirtyKeys.contains(cleanVariant)) {
                            // Run multi-stage validation (Jaro-Winkler + LLM-as-a-Judge)
                            UnitDictionaryEntity validatedEntity = validationService.validateAndBuildEntity(
                                    cleanVariant, canonical, isKnownCanonical);

                            allKnownDirtyKeys.add(cleanVariant);
                            batchEntries.add(validatedEntity);

                            if (validatedEntity.getStatus() == DictionaryStatus.AUTO_VERIFIED) {
                                activeDictionary.put(cleanVariant, canonical);
                                existingCanonicals.add(canonical);
                            }
                        }
                    }
                }

                // Checkpoint: persist each batch immediately to the DB so progress is not lost on failures
                if (!batchEntries.isEmpty()) {
                    repository.saveNewDictionaryEntries(batchEntries);
                    log.info("Batch {}/{}: saved {} entries to MDM_Unit_Dictionary.",
                            currentBatchNum, totalBatches, batchEntries.size());
                }
            }
        }


        // 4. Second pass: select only the delta of records (UIDDocument, Line, DateStart) requiring substitution
        List<GoodsSpaceTypeEntity> recordsToFix = repository.fetchRecordsRequiringNormalization(
                sourceViewName, activeDictionary);
        log.info("Found {} records requiring normalization for GoodsWholesale_AISpaceType.", recordsToFix.size());

        if (!recordsToFix.isEmpty()) {
            repository.saveNormalizedRecordsBatch(recordsToFix);
        }

        return recordsToFix.size();
    }
}
