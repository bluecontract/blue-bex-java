package blue.bex.output;

import blue.language.snapshot.FrozenNode;
import blue.language.identity.BlueIds;

import java.util.Objects;

/**
 * Exact Blue identity established by a semantic output boundary.
 *
 * <p>The frozen value is the boundary's exact semantic result. BEX retains it
 * directly so hosted execution never independently reconstructs or hashes a
 * value after the host has admitted it.</p>
 */
public final class BexEstablishedIdentity {
    private final String blueId;
    private final FrozenNode frozenValue;
    private final BexExactValueCapability exactCapability;

    public BexEstablishedIdentity(String blueId, FrozenNode frozenValue) {
        this(blueId, frozenValue, null);
    }

    public BexEstablishedIdentity(
            String blueId,
            FrozenNode frozenValue,
            BexExactValueCapability exactCapability) {
        this.blueId = BlueIds.requireBlueIdOrCyclicMember(
                Objects.requireNonNull(blueId, "blueId"),
                "BEX established output blueId");
        this.frozenValue = Objects.requireNonNull(
                frozenValue, "frozenValue");
        this.exactCapability = exactCapability;
    }

    public String blueId() {
        return blueId;
    }

    public FrozenNode frozenValue() {
        return frozenValue;
    }

    /**
     * Returns the optional opaque capability supplied by the host boundary.
     *
     * @return the host capability, or {@code null} when none was supplied
     */
    public BexExactValueCapability exactCapability() {
        return exactCapability;
    }
}
