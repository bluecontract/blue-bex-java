package blue.bex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Retryable BEX suspension caused by unavailable exact Blue evidence.
 */
public final class BexExecutionEvidenceUnavailableException
        extends BexException {
    private static final long serialVersionUID = 1L;

    private final List<String> requiredExactBlueIds;

    public BexExecutionEvidenceUnavailableException(String message) {
        this(message, Collections.<String>emptyList());
    }

    public BexExecutionEvidenceUnavailableException(
            String message,
            Collection<String> requiredExactBlueIds) {
        super(Objects.requireNonNull(message, "message"));
        Objects.requireNonNull(requiredExactBlueIds, "requiredExactBlueIds");
        TreeSet<String> sorted = new TreeSet<>();
        for (String blueId : requiredExactBlueIds) {
            if (blueId == null || blueId.isEmpty()) {
                throw new IllegalArgumentException(
                        "Required exact BlueIds must be non-empty");
            }
            sorted.add(blueId);
        }
        this.requiredExactBlueIds = Collections.unmodifiableList(
                new ArrayList<>(sorted));
    }

    public List<String> requiredExactBlueIds() {
        return requiredExactBlueIds;
    }
}
