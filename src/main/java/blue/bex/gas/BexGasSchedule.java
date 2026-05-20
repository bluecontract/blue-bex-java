package blue.bex.gas;

/**
 * Deterministic gas cost schedule.
 *
 * <p>The schedule assigns integer costs to compiled-expression operations. For a
 * fixed program, context, and schedule, gas usage is deterministic.</p>
 */
public final class BexGasSchedule {
    public final long expressionBase;
    public final long statementBase;
    public final long documentRead;
    public final long eventRead;
    public final long stepsRead;
    public final long currentContractRead;
    public final long varRead;
    public final long resultValueRead;
    public final long pointerGetBase;
    public final long pointerSetBase;
    public final long objectSetBase;
    public final long appendChangeBase;
    public final long appendEventBase;
    public final long forEachItem;
    public final long functionCall;

    private BexGasSchedule(Builder builder) {
        this.expressionBase = builder.expressionBase;
        this.statementBase = builder.statementBase;
        this.documentRead = builder.documentRead;
        this.eventRead = builder.eventRead;
        this.stepsRead = builder.stepsRead;
        this.currentContractRead = builder.currentContractRead;
        this.varRead = builder.varRead;
        this.resultValueRead = builder.resultValueRead;
        this.pointerGetBase = builder.pointerGetBase;
        this.pointerSetBase = builder.pointerSetBase;
        this.objectSetBase = builder.objectSetBase;
        this.appendChangeBase = builder.appendChangeBase;
        this.appendEventBase = builder.appendEventBase;
        this.forEachItem = builder.forEachItem;
        this.functionCall = builder.functionCall;
    }

    public static BexGasSchedule defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long expressionBase = 1;
        private long statementBase = 1;
        private long documentRead = 2;
        private long eventRead = 1;
        private long stepsRead = 1;
        private long currentContractRead = 1;
        private long varRead = 1;
        private long resultValueRead = 2;
        private long pointerGetBase = 1;
        private long pointerSetBase = 3;
        private long objectSetBase = 2;
        private long appendChangeBase = 5;
        private long appendEventBase = 5;
        private long forEachItem = 1;
        private long functionCall = 2;

        public Builder expressionBase(long value) { expressionBase = value; return this; }
        public Builder statementBase(long value) { statementBase = value; return this; }
        public Builder documentRead(long value) { documentRead = value; return this; }
        public Builder eventRead(long value) { eventRead = value; return this; }
        public Builder stepsRead(long value) { stepsRead = value; return this; }
        public Builder currentContractRead(long value) { currentContractRead = value; return this; }
        public Builder varRead(long value) { varRead = value; return this; }
        public Builder resultValueRead(long value) { resultValueRead = value; return this; }
        public Builder pointerGetBase(long value) { pointerGetBase = value; return this; }
        public Builder pointerSetBase(long value) { pointerSetBase = value; return this; }
        public Builder objectSetBase(long value) { objectSetBase = value; return this; }
        public Builder appendChangeBase(long value) { appendChangeBase = value; return this; }
        public Builder appendEventBase(long value) { appendEventBase = value; return this; }
        public Builder forEachItem(long value) { forEachItem = value; return this; }
        public Builder functionCall(long value) { functionCall = value; return this; }
        public BexGasSchedule build() { return new BexGasSchedule(this); }
    }
}
