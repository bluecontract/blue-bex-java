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

    public BexEstablishedIdentity(String blueId, FrozenNode frozenValue) {
        this.blueId = BlueIds.requireBlueIdOrCyclicMember(
                Objects.requireNonNull(blueId, "blueId"),
                "BEX established output blueId");
        this.frozenValue = Objects.requireNonNull(
                frozenValue, "frozenValue");
    }

    public String blueId() {
        return blueId;
    }

    public FrozenNode frozenValue() {
        return frozenValue;
    }
}
