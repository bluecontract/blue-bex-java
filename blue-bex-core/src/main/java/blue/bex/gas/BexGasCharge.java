package blue.bex.gas;

import java.util.Objects;

/**
 * One immutable, admitted entry in the canonical BEX child-ledger trace.
 *
 * <p>The portable BEX counters use namespace {@code bex}. Registry-bound
 * intrinsic child counters retain their own namespace and name without
 * expanding the closed {@link BexGasCounter} enum.</p>
 */
public final class BexGasCharge {
    private final long sequence;
    private final String namespace;
    private final BexGasCounter counter;
    private final String counterName;
    private final long quantity;
    private final long weight;
    private final long gas;
    private final String sourcePath;
    private final String operator;
    private final String reason;

    public BexGasCharge(long sequence,
                        BexGasCounter counter,
                        long quantity,
                        long weight,
                        String sourcePath,
                        String operator,
                        String reason) {
        this(sequence,
                BexGasCounter.NAMESPACE,
                Objects.requireNonNull(counter, "counter"),
                counter.canonicalName(),
                quantity,
                weight,
                multiplyExact(quantity, weight),
                sourcePath,
                operator,
                reason);
    }

    /**
     * Creates a portable trace entry while validating the supplied derived gas
     * value. This overload is useful when reconstructing fixture traces.
     */
    public BexGasCharge(long sequence,
                        BexGasCounter counter,
                        long quantity,
                        long weight,
                        long gas,
                        String sourcePath,
                        String operator,
                        String reason) {
        this(sequence,
                BexGasCounter.NAMESPACE,
                Objects.requireNonNull(counter, "counter"),
                counter.canonicalName(),
                quantity,
                weight,
                gas,
                sourcePath,
                operator,
                reason);
    }

    public BexGasCharge(long sequence,
                        String namespace,
                        String counterName,
                        long quantity,
                        long weight,
                        String sourcePath,
                        String operator,
                        String reason) {
        this(sequence,
                namespace,
                portableCounter(namespace, counterName),
                counterName,
                quantity,
                weight,
                multiplyExact(quantity, weight),
                sourcePath,
                operator,
                reason);
    }

    public BexGasCharge(long sequence,
                        String namespace,
                        String counterName,
                        long quantity,
                        long weight,
                        long gas,
                        String sourcePath,
                        String operator,
                        String reason) {
        this(sequence,
                namespace,
                portableCounter(namespace, counterName),
                counterName,
                quantity,
                weight,
                gas,
                sourcePath,
                operator,
                reason);
    }

    private BexGasCharge(long sequence,
                         String namespace,
                         BexGasCounter counter,
                         String counterName,
                         long quantity,
                         long weight,
                         long gas,
                         String sourcePath,
                         String operator,
                         String reason) {
        if (sequence < 0L) {
            throw new IllegalArgumentException("Gas sequence must be non-negative");
        }
        if (quantity < 0L) {
            throw new IllegalArgumentException("Gas quantity must be non-negative");
        }
        if (weight < 0L) {
            throw new IllegalArgumentException("Gas weight must be non-negative");
        }
        long expectedGas = multiplyExact(quantity, weight);
        if (gas != expectedGas) {
            throw new IllegalArgumentException(
                    "Gas must equal quantity * weight: expected "
                            + expectedGas + " but was " + gas);
        }
        this.sequence = sequence;
        this.namespace = requireName(namespace, "Gas namespace");
        this.counter = counter;
        this.counterName = requireName(counterName, "Gas counter");
        this.quantity = quantity;
        this.weight = weight;
        this.gas = gas;
        this.sourcePath = emptyToNull(sourcePath);
        this.operator = emptyToNull(operator);
        this.reason = requireReason(reason);
    }

    public long sequence() {
        return sequence;
    }

    public String namespace() {
        return namespace;
    }

    /**
     * Returns the portable BEX counter, or {@code null} for a registry-bound
     * named child counter.
     */
    public BexGasCounter counter() {
        return counter;
    }

    /** Alias for {@link #counter()}. */
    public BexGasCounter portableCounter() {
        return counter;
    }

    public String counterName() {
        return counterName;
    }

    /**
     * Returns the key used for this entry in the enclosing host child ledger.
     */
    public String qualifiedCounterName() {
        return BexGasMeter.qualifiedCounterName(namespace, counterName);
    }

    public long quantity() {
        return quantity;
    }

    public long weight() {
        return weight;
    }

    public long gas() {
        return gas;
    }

    /** Returns the optional canonical source path, or {@code null}. */
    public String sourcePath() {
        return sourcePath;
    }

    /** Returns the optional canonical operator name, or {@code null}. */
    public String operator() {
        return operator;
    }

    public String reason() {
        return reason;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String requireReason(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Gas charge reason is required");
        }
        return value;
    }

    private static String requireName(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static BexGasCounter portableCounter(String namespace,
                                                String counterName) {
        if (!BexGasCounter.NAMESPACE.equals(namespace) || counterName == null) {
            return null;
        }
        try {
            return BexGasCounter.fromCanonicalName(counterName);
        } catch (IllegalArgumentException notPortable) {
            return null;
        }
    }

    private static long multiplyExact(long left, long right) {
        if (left < 0L || right < 0L) {
            throw new IllegalArgumentException(
                    "Gas quantity and weight must be non-negative");
        }
        if (left != 0L && right > Long.MAX_VALUE / left) {
            throw new IllegalArgumentException("Gas subtotal exceeds long range");
        }
        return left * right;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BexGasCharge)) {
            return false;
        }
        BexGasCharge that = (BexGasCharge) other;
        return sequence == that.sequence
                && quantity == that.quantity
                && weight == that.weight
                && gas == that.gas
                && counter == that.counter
                && namespace.equals(that.namespace)
                && counterName.equals(that.counterName)
                && Objects.equals(sourcePath, that.sourcePath)
                && Objects.equals(operator, that.operator)
                && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sequence,
                namespace,
                counter,
                counterName,
                quantity,
                weight,
                gas,
                sourcePath,
                operator,
                reason);
    }

    @Override
    public String toString() {
        return "BexGasCharge{"
                + "sequence=" + sequence
                + ", namespace='" + namespace + '\''
                + ", counter='" + counterName + '\''
                + ", quantity=" + quantity
                + ", weight=" + weight
                + ", gas=" + gas
                + ", sourcePath='" + sourcePath + '\''
                + ", operator='" + operator + '\''
                + ", reason='" + reason + '\''
                + '}';
    }
}
