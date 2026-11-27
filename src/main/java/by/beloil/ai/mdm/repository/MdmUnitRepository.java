package by.beloil.ai.mdm.repository;

import by.beloil.ai.mdm.hibernate.HibernateBatchExecutor;
import by.beloil.ai.mdm.model.GoodsSpaceTypeEntity;
import by.beloil.ai.mdm.model.GoodsSpaceTypePK;
import by.beloil.ai.mdm.model.UnitDictionaryEntity;
import com.google.inject.Inject;
import lombok.RequiredArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Repository responsible for reading and persisting Master Data Management (MDM)
 * unit normalization rules and resolved wholesale goods space type overrides.
 */
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MdmUnitRepository {

    private final HibernateBatchExecutor dbExecutor;

    /**
     * Loads all entries from {@code [dwbeloil].[dbo].[MDM_Unit_Dictionary]} in a single query
     * so the pipeline can partition them into a cache of all processed keys and a map of active rules.
     *
     * @return complete list of {@link UnitDictionaryEntity} records
     */
    public List<UnitDictionaryEntity> loadAllDictionaryEntries() {
        return dbExecutor.executeRead("loadAllDictionaryEntries", session ->
                session.createQuery("FROM UnitDictionaryEntity", UnitDictionaryEntity.class)
                       .getResultList()
        );
    }

    /**
     * Retrieves all distinct, non-null raw {@code st_Name} unit values from the specified source view.
     *
     * @param sourceViewName name of the database view containing raw wholesale goods data
     * @return list of distinct raw unit strings
     */
    public List<String> fetchDistinctDirtyUnits(String sourceViewName) {
        return dbExecutor.executeRead("fetchDistinctDirtyUnits", session -> {
            String sql = "SELECT DISTINCT st_Name FROM " + sourceViewName + " WHERE st_Name IS NOT NULL";
            return session.createNativeQuery(sql, String.class).getResultList();
        });
    }

    /**
     * Batch-persists newly discovered normalization rules (including {@code Status} and {@code ValidationReason})
     * into {@code dbo.MDM_Unit_Dictionary}.
     *
     * @param entries list of new dictionary entities to save
     */
    public void saveNewDictionaryEntries(List<UnitDictionaryEntity> entries) {
        dbExecutor.saveBatch("saveNewDictionaryEntries", entries);
    }

    /**
     * Selects only the delta of source records ({@code UIDDocument}, {@code Line}, {@code DateStart}) where:
     * <ol>
     *     <li>The raw {@code st_Name} differs from the active canonical name in the dictionary; and</li>
     *     <li>The target table {@code dbo.GoodsWholesale_AISpaceType} either lacks a record or holds an outdated
     *         canonical value or source timestamp.</li>
     * </ol>
     *
     * @param sourceViewName   name of the source database view
     * @param activeDictionary map of approved/auto-verified raw unit spellings to canonical designations
     * @return list of {@link GoodsSpaceTypeEntity} records requiring insertion or update
     */
    public List<GoodsSpaceTypeEntity> fetchRecordsRequiringNormalization(
            String sourceViewName, Map<String, String> activeDictionary) {

        return dbExecutor.executeRead("fetchRecordsRequiringNormalization", session -> {
            String sql = """
                SELECT ws.UIDDocument, ws.Line, ws.st_Name, ws.DateStart, st.SpaceType, st.DateStart
                FROM %s AS ws
                LEFT JOIN [dwbeloil].[dbo].[GoodsWholesale_AISpaceType] AS st
                    ON st.UIDDocument = ws.UIDDocument AND st.Line = ws.Line
                WHERE ws.st_Name IS NOT NULL
                """.formatted(sourceViewName);

            List<Object[]> rows = session.createNativeQuery(sql, Object[].class).getResultList();
            List<GoodsSpaceTypeEntity> delta = new ArrayList<>();

            for (Object[] row : rows) {
                byte[] uidDoc = (byte[]) row[0];
                Integer line = ((Number) row[1]).intValue();
                String rawStName = (String) row[2];
                LocalDateTime sourceDateStart = toLocalDateTime(row[3]);
                String existingOverride = (String) row[4];
                LocalDateTime existingDateStart = toLocalDateTime(row[5]);

                String canonical = activeDictionary.get(rawStName.trim());

                // Skip if the value is not present among active rules or already matches the canonical form
                if (canonical == null || rawStName.equals(canonical)) {
                    continue;
                }

                // Skip if the target override table already stores the up-to-date canonical value and timestamp
                if (canonical.equals(existingOverride) && Objects.equals(sourceDateStart, existingDateStart)) {
                    continue;
                }

                delta.add(new GoodsSpaceTypeEntity(
                        new GoodsSpaceTypePK(uidDoc, line),
                        canonical,
                        sourceDateStart
                ));
            }
            return delta;
        });
    }

    /**
     * Batch-persists (INSERT/UPDATE) normalized records into {@code [dwbeloil].[dbo].[GoodsWholesale_AISpaceType]}.
     *
     * @param records list of normalized entities to save
     */
    public void saveNormalizedRecordsBatch(List<GoodsSpaceTypeEntity> records) {
        dbExecutor.saveBatch("saveNormalizedRecordsBatch", records);
    }

    /**
     * Safely converts a JDBC date/timestamp object or formatted date string returned by a native query
     * into a {@link LocalDateTime}. Supports native timestamp types, compact 8-digit SAP BW date strings
     * ({@code yyyyMMdd}), and standard SQL/ISO datetime strings.
     *
     * @param dbValue raw column value from the database result set
     * @return converted {@link LocalDateTime}, or {@code null} if the value is {@code null} or blank
     * @throws IllegalArgumentException if the returned object type is unsupported
     */
    private LocalDateTime toLocalDateTime(Object dbValue) {
        if (dbValue == null) {
            return null;
        }
        if (dbValue instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (dbValue instanceof Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (dbValue instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            // Handle compact SAP BW 'yyyyMMdd' date format (e.g., '20261126')
            if (trimmed.length() == 8 && trimmed.chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(trimmed, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay();
            }
            // Handle standard SQL 'yyyy-MM-dd HH:mm:ss' or ISO datetime formats
            String normalized = trimmed.replace(' ', 'T');
            return LocalDateTime.parse(normalized);
        }
        throw new IllegalArgumentException("Unexpected date type returned from database: " + dbValue.getClass().getName());
    }
}

