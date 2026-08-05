package blue.bex.gas;

/** Immutable host-neutral attribution for a named gas charge. */
public final class BexGasChargeContext {
    private static final BexGasChargeContext EMPTY =
            new BexGasChargeContext(null, null, null, "unspecified");

    private final String scopePath;
    private final String contractKey;
    private final String logicalPath;
    private final String reason;

    private BexGasChargeContext(
            String scopePath,
            String contractKey,
            String logicalPath,
            String reason) {
        this.scopePath = scopePath;
        this.contractKey = contractKey;
        this.logicalPath = logicalPath;
        this.reason = reason != null ? reason : "unspecified";
    }

    public static BexGasChargeContext empty() {
        return EMPTY;
    }

    public static BexGasChargeContext of(
            String scopePath,
            String contractKey,
            String logicalPath,
            String reason) {
        return new BexGasChargeContext(
                scopePath, contractKey, logicalPath, reason);
    }

    public String scopePath() { return scopePath; }
    public String contractKey() { return contractKey; }
    public String logicalPath() { return logicalPath; }
    public String reason() { return reason; }
}
