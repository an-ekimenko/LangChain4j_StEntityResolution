package by.beloil.ai.mdm.model;

public enum DictionaryStatus {
    AUTO_VERIFIED, // Passed all automated checks (active for normalization)
    NEEDS_REVIEW,  // Quarantined due to validation warning or new unapproved canonical unit
    APPROVED,      // Manually approved by MDM engineer (active for normalization)
    REJECTED       // Manually rejected (ignored during normalization)
}
