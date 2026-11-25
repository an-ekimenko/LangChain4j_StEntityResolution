package by.beloil.ai.mdm.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Composite primary key for {@link GoodsSpaceTypeEntity}, uniquely identifying
 * a specific line item within a wholesale goods document.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GoodsSpaceTypePK implements Serializable {

    /**
     * Unique binary identifier of the source document (17-byte binary UUID, e.g., from 1C).
     */
    @Column(name = "UIDDocument", columnDefinition = "binary(17)", nullable = false)
    private byte[] uidDocument;

    /**
     * Line item number within the source document.
     */
    @Column(name = "Line", nullable = false)
    private Integer line;

    /**
     * Compares two composite keys for equality using deep array comparison for {@code uidDocument}.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GoodsSpaceTypePK that)) return false;
        return Arrays.equals(uidDocument, that.uidDocument) && Objects.equals(line, that.line);
    }

    /**
     * Computes the hash code using the array contents of {@code uidDocument} and the {@code line} number.
     */
    @Override
    public int hashCode() {
        int result = Objects.hash(line);
        result = 31 * result + Arrays.hashCode(uidDocument);
        return result;
    }
}
