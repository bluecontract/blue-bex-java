package blue.bex.gas;

import blue.bex.BexException;
import blue.language.processor.GasLimitExceededException;

/**
 * Raised before work when the next named BEX charge cannot be admitted.
 */
public final class BexGasLimitExceededException extends BexException {
    private static final long serialVersionUID = 1L;

    private final String namespace;
    private final BexGasCounter counter;
    private final String counterName;
    private final long quantity;
    private final long weight;
    private final long admittedGas;
    private final long effectiveBudget;
    private final GasLimitExceededException hostGasLimitExceeded;

    BexGasLimitExceededException(BexGasCounter counter,
                                 long quantity,
                                 long weight,
                                 long admittedGas,
                                 long effectiveBudget) {
        this(BexGasCounter.NAMESPACE,
                counter,
                counter.canonicalName(),
                quantity,
                weight,
                admittedGas,
                effectiveBudget,
                null);
    }

    BexGasLimitExceededException(String namespace,
                                 String counterName,
                                 long quantity,
                                 long weight,
                                 long admittedGas,
                                 long effectiveBudget) {
        this(namespace,
                null,
                counterName,
                quantity,
                weight,
                admittedGas,
                effectiveBudget,
                null);
    }

    BexGasLimitExceededException(BexGasCounter counter,
                                 long quantity,
                                 long weight,
                                 long admittedGas,
                                 long effectiveBudget,
                                 GasLimitExceededException
                                         hostGasLimitExceeded) {
        this(BexGasCounter.NAMESPACE,
                counter,
                counter.canonicalName(),
                quantity,
                weight,
                admittedGas,
                effectiveBudget,
                hostGasLimitExceeded);
    }

    BexGasLimitExceededException(String namespace,
                                 String counterName,
                                 long quantity,
                                 long weight,
                                 long admittedGas,
                                 long effectiveBudget,
                                 GasLimitExceededException
                                         hostGasLimitExceeded) {
        this(namespace,
                null,
                counterName,
                quantity,
                weight,
                admittedGas,
                effectiveBudget,
                hostGasLimitExceeded);
    }

    private BexGasLimitExceededException(String namespace,
                                         BexGasCounter counter,
                                         String counterName,
                                         long quantity,
                                         long weight,
                                         long admittedGas,
                                         long effectiveBudget,
                                         GasLimitExceededException
                                                 hostGasLimitExceeded) {
        super("BEX gas exhausted before " + namespace + "." + counterName
                + " at " + admittedGas + " of " + effectiveBudget
                + " gas units",
                hostGasLimitExceeded);
        this.namespace = namespace;
        this.counter = counter;
        this.counterName = counterName;
        this.quantity = quantity;
        this.weight = weight;
        this.admittedGas = admittedGas;
        this.effectiveBudget = effectiveBudget;
        this.hostGasLimitExceeded = hostGasLimitExceeded;
    }

    public BexGasCounter counter() {
        return counter;
    }

    public String namespace() {
        return namespace;
    }

    public String counterName() {
        return counterName;
    }

    public long quantity() {
        return quantity;
    }

    public long weight() {
        return weight;
    }

    public long admittedGas() {
        return admittedGas;
    }

    public long effectiveBudget() {
        return effectiveBudget;
    }

    /**
     * Returns the exact host rejection which caused this BEX failure, or
     * {@code null} when the stricter BEX-local sub-limit rejected the charge
     * before the host ledger was touched.
     */
    public GasLimitExceededException hostGasLimitExceeded() {
        return hostGasLimitExceeded;
    }
}
