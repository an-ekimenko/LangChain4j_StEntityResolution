package by.beloil.ai.mdm.repository;

import by.beloil.ai.mdm.hibernate.HibernateBatchExecutor;
import by.beloil.ai.mdm.model.GoodsSpaceTypeEntity;
import by.beloil.ai.mdm.model.GoodsSpaceTypePK;
import by.beloil.ai.mdm.model.UnitDictionaryEntity;
import com.google.inject.Inject;
import lombok.RequiredArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Repository responsible for reading and persisting Master Data Management (MDM)
 * unit normalization dictionaries and resolved wholesale goods space type records.
 */
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class MdmUnitRepository {

    private final HibernateBatchExecutor dbExecutor;

    /**
     * Loads the entire unit normalization dictionary from the database into an in-memory map.
     *
     * @return map associating raw unit spellings ({@code DirtyName}) with standardized names ({@code CanonicalName})
     */
    public Map<String, String> loadDictionary() {
        return dbExecutor.executeRead("loadDictionary", session -> {
            List<UnitDictionaryEntity> list = session
                    .createQuery("FROM UnitDictionaryEntity", UnitDictionaryEntity.class)
                    .getResultList();
            Map<String, String> map = new HashMap<>(list.size());
            for (UnitDictionaryEntity entity : list) {
                map.put(entity.getDirtyName(), entity.getCanonicalName());
            }
            return map;
        });
    }

    /**
     * Fetches all distinct, non-null raw unit of measurement names from the specified source view.
     *
     * @param sourceViewName name of the database view containing raw wholesale goods data
     * @return list of distinct raw {@code st_Name} strings
     */
    public List<String> fetchDistinctDirtyUnits(String sourceViewName) {
        return dbExecutor.executeRead("fetchDistinctDirtyUnits", session -> {
            String sql = "SELECT DISTINCT st_Name FROM " + sourceViewName + " WHERE st_Name IS NOT NULL";
            return session.createNativeQuery(sql, String.class).getResultList();
        });
    }

    /**
     * Batch-persists newly discovered unit mapping rules into {@code dbo.MDM_Unit_Dictionary}.
     *
     * @param entries list of new dictionary entities to insert or update
     */
    public void saveNewDictionaryEntries(List<UnitDictionaryEntity> entries) {
        dbExecutor.saveBatch("saveNewDictionaryEntries", entries);
    }

    /**
     * Identifies source records whose raw unit spelling differs from the resolved canonical name
     * and computes the delta of entities requiring insertion or update in {@code dbo.GoodsWholesale_AISpaceType}.
     *
     * @param sourceViewName name of the source database view
     * @param dictionary     in-memory map of raw unit spellings to canonical designations
     * @return list of {@link GoodsSpaceTypeEntity} records that need to be persisted or updated
     */
    public List<GoodsSpaceTypeEntity> fetchRecordsRequiringNormalization(
            String sourceViewName, Map<String, String> dictionary) {

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

                String canonical = dictionary.get(rawStName.trim());

                // Skip if no mapping exists or if the raw value already matches the canonical form
                if (canonical == null || rawStName.equals(canonical)) {
                    continue;
                }
                // Skip if the target table already holds the exact same canonical value and source timestamp
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
     * Batch-persists normalized wholesale goods records into {@code dbo.GoodsWholesale_AISpaceType}.
     *
     * @param records list of normalized entities to save
     */
    public void saveNormalizedRecordsBatch(List<GoodsSpaceTypeEntity> records) {
        dbExecutor.saveBatch("saveNormalizedRecordsBatch", records);
    }

    /**
     * Safely converts a JDBC date/timestamp object or ISO-like string returned by a native query
     * into a {@link LocalDateTime}.
     *
     * @param dbValue raw column value from the database result set
     * @return converted {@link LocalDateTime}, or {@code null} if the value is {@code null} or blank
     * @throws IllegalArgumentException if the returned object type is unsupported
     */
    private LocalDateTime toLocalDateTime(Object dbValue) {
        if (dbValue == null) return null;

        if (dbValue instanceof LocalDateTime ldt) return ldt;

        if (dbValue instanceof Timestamp ts) return ts.toLocalDateTime();

        if (dbValue instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.isEmpty()) return null;

            // MS SQL frequently returns "yyyy-MM-dd HH:mm:ss[.SSS]" or ISO "yyyy-MM-dd'T'HH:mm:ss"
            String normalized = trimmed.replace(' ', 'T');
            return LocalDateTime.parse(normalized);
        }

        throw new IllegalArgumentException("Unexpected date type returned from database: " + dbValue.getClass().getName());
    }


}
