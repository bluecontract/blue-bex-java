package blue.bex.gas;

import blue.bex.BexException;

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
    private final BexHostGasExhaustion hostGasExhaustion;

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
                                 BexHostGasExhaustion hostGasExhaustion) {
        this(BexGasCounter.NAMESPACE,
                counter,
                counter.canonicalName(),
                quantity,
                weight,
                admittedGas,
                effectiveBudget,
                hostGasExhaustion);
    }

    BexGasLimitExceededException(String namespace,
                                 String counterName,
                                 long quantity,
                                 long weight,
                                 long admittedGas,
                                 long effectiveBudget,
                                 BexHostGasExhaustion hostGasExhaustion) {
        this(namespace,
                null,
                counterName,
                quantity,
                weight,
                admittedGas,
                effectiveBudget,
                hostGasExhaustion);
    }

    private BexGasLimitExceededException(String namespace,
                                         BexGasCounter counter,
                                         String counterName,
                                         long quantity,
                                         long weight,
                                         long admittedGas,
                                         long effectiveBudget,
                                         BexHostGasExhaustion
                                                 hostGasExhaustion) {
        super("BEX gas exhausted before " + namespace + "." + counterName
                + " at " + admittedGas + " of " + effectiveBudget
                + " gas units",
                hostGasExhaustion);
        this.namespace = namespace;
        this.counter = counter;
        this.counterName = counterName;
        this.quantity = quantity;
        this.weight = weight;
        this.admittedGas = admittedGas;
        this.effectiveBudget = effectiveBudget;
        this.hostGasExhaustion = hostGasExhaustion;
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
    public BexHostGasExhaustion hostGasExhaustion() {
        return hostGasExhaustion;
    }
}
