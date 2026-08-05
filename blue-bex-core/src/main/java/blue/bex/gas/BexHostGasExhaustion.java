package blue.bex.gas;

import java.util.Objects;

/**
 * Host-neutral rejected-charge evidence retaining the exact native host
 * exception as an opaque capability.
 */
public final class BexHostGasExhaustion extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String namespace;
    private final String counterName;
    private final long quantity;
    private final long weight;
    private final long admittedGas;
    private final long effectiveBudget;
    private final RuntimeException hostFailure;

    public BexHostGasExhaustion(
            String namespace,
            String counterName,
            long quantity,
            long weight,
            long admittedGas,
            long effectiveBudget,
            RuntimeException hostFailure) {
        super("Host gas exhausted before " + namespace + "." + counterName,
                Objects.requireNonNull(hostFailure, "hostFailure"));
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.counterName = Objects.requireNonNull(counterName, "counterName");
        this.quantity = quantity;
        this.weight = weight;
        this.admittedGas = admittedGas;
        this.effectiveBudget = effectiveBudget;
        this.hostFailure = hostFailure;
    }

    public String namespace() { return namespace; }
    public String counterName() { return counterName; }
    public long quantity() { return quantity; }
    public long weight() { return weight; }
    public long admittedGas() { return admittedGas; }
    public long effectiveBudget() { return effectiveBudget; }
    public RuntimeException hostFailure() { return hostFailure; }
}
