package blue.bex.contracts;

import blue.bex.output.BexExactValueCapability;
import blue.language.processor.ExactBlueValue;

import java.util.Objects;

/**
 * Processor-hosted BEX capability for one invocation-admitted exact value.
 *
 * <p>The constructor is package-private so only the Contracts bridge can wrap
 * a Language-issued handle. Consumers may carry the handle back into the same
 * processor invocation; Language revalidates ownership at that boundary.</p>
 */
public final class ProcessorExactBlueValueCapability
        implements BexExactValueCapability {
    private final ExactBlueValue exactValue;

    ProcessorExactBlueValueCapability(ExactBlueValue exactValue) {
        this.exactValue = Objects.requireNonNull(exactValue, "exactValue");
    }

    /**
     * Returns the Language-issued invocation capability.
     *
     * @return the exact value capability owned by the current invocation
     */
    public ExactBlueValue exactValue() {
        return exactValue;
    }
}
