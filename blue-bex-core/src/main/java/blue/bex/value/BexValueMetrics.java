package blue.bex.value;

/** Narrow conversion metrics port used by value writers. */
public interface BexValueMetrics {
    void incrementFrozenOutputConversions();
    void incrementFrozenWriterNodeFallbacks();
}
