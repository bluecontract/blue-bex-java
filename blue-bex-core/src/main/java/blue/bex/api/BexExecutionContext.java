package blue.bex.api;

import blue.bex.output.BexSemanticIdentityBoundary;
import blue.bex.gas.BexGasMeter;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.bex.runtime.BexRuntimeContext;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Immutable execution context for one BEX run.
 *
 * <p>The context supplies the document view, host-provided bindings, standard
 * convenience bindings, and gas limit. The current scope path is derived from
 * the configured {@link BexDocumentView}; callers should not maintain a
 * separate scope value.</p>
 */
public final class BexExecutionContext implements BexRuntimeContext {
    private final BexDocumentView document;
    private final Map<String, BindingSlot> bindingSlots;
    private final BexValue event;
    private final BexValue processingEvent;
    private final BexValue currentContract;
    private final BexStepResults steps;
    private final long parentRemainingGas;
    private final long gasLimit;
    private final BexGasLedgerHost gasLedgerHost;
    private final BexSemanticIdentityBoundary semanticIdentityBoundary;
    private final BexFailureBoundary failureBoundary;
    private final ResolutionCoordinator resolutionCoordinator;
    private volatile Map<String, BexValue> materializedBindings;

    private BexExecutionContext(Builder builder) {
        this.document = builder.document;
        this.steps = builder.steps != null ? builder.steps : BexStepResults.empty();
        this.parentRemainingGas = builder.parentRemainingGas;
        this.gasLimit = builder.gasLimit;
        this.gasLedgerHost = builder.gasLedgerHost;
        if (builder.semanticIdentityBoundary == null
                && builder.gasLedgerHost != null) {
            throw new IllegalArgumentException(
                    "Hosted BEX execution requires an explicit "
                            + "semantic identity boundary");
        }
        this.semanticIdentityBoundary =
                builder.semanticIdentityBoundary != null
                        ? builder.semanticIdentityBoundary
                        : BexSemanticIdentityBoundary.STANDALONE;
        this.failureBoundary = builder.failureBoundary != null
                ? builder.failureBoundary
                : BexFailureBoundary.STANDALONE;
        if (document == null) {
            throw new IllegalArgumentException("document is required");
        }
        this.resolutionCoordinator = new ResolutionCoordinator();
        LinkedHashMap<String, BindingSlot> copy = new LinkedHashMap<>();
        for (Map.Entry<String, BindingDefinition> entry : builder.bindings.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().createSlot(entry.getKey(), resolutionCoordinator));
        }
        if (builder.steps != null && !copy.containsKey("steps")) {
            copy.put("steps", new EagerBindingSlot(builder.steps.asValue()));
        }
        this.bindingSlots = Collections.unmodifiableMap(copy);
        this.event = bindingFrom(copy, "event");
        this.processingEvent = bindingFrom(copy, "processingEvent");
        this.currentContract = bindingFrom(copy, "currentContract");
    }

    public static Builder builder() {
        return new Builder();
    }

    public BexDocumentView document() {
        return document;
    }

    public BexValue event() {
        return event;
    }

    /**
     * Original external Processing Event, distinct from the current channel
     * event used by triggered/lifecycle/embedded delivery.
     */
    public BexValue processingEvent() {
        return processingEvent;
    }

    public BexValue currentContract() {
        return currentContract;
    }

    public BexStepResults steps() {
        return steps;
    }

    public BexValue binding(String name) {
        return bindingFrom(bindingSlots, name);
    }

    /**
     * Returns all host bindings as concrete BEX values.
     *
     * <p>Calling this method materializes every lazy binding in declaration
     * order. The returned map is unmodifiable and contains no lazy wrappers.
     * If a supplier fails, materialization stops at that binding and the
     * failure is retained by its slot for later reads.</p>
     *
     * @return concrete host bindings in declaration order
     */
    public Map<String, BexValue> bindings() {
        Map<String, BexValue> cached = materializedBindings;
        if (cached != null) {
            return cached;
        }

        LinkedHashMap<String, BexValue> values = new LinkedHashMap<>();
        for (Map.Entry<String, BindingSlot> entry : bindingSlots.entrySet()) {
            values.put(entry.getKey(), entry.getValue().resolve());
        }
        Map<String, BexValue> materialized = Collections.unmodifiableMap(values);
        materializedBindings = materialized;
        return materialized;
    }

    public String currentScopePath() {
        return document.currentScopePath();
    }

    public long gasLimit() {
        return gasLimit;
    }

    /**
     * Standalone parent budget. Hosted execution obtains the exact value from
     * the live child ledger instead.
     */
    public long parentRemainingGas() {
        return parentRemainingGas;
    }

    public BexGasLedgerHost gasLedgerHost() {
        return gasLedgerHost;
    }

    public BexSemanticIdentityBoundary semanticIdentityBoundary() {
        return semanticIdentityBoundary;
    }

    public BexFailureBoundary failureBoundary() {
        return failureBoundary;
    }

    public static final class Builder {
        private BexDocumentView document;
        private BexStepResults steps;
        private long parentRemainingGas = Long.MAX_VALUE;
        private long gasLimit = BexGasMeter.NO_LOCAL_LIMIT;
        private BexGasLedgerHost gasLedgerHost;
        private BexSemanticIdentityBoundary semanticIdentityBoundary;
        private BexFailureBoundary failureBoundary;
        private final LinkedHashMap<String, BindingDefinition> bindings = new LinkedHashMap<>();

        public Builder document(BexDocumentView document) {
            this.document = document;
            return this;
        }

        public Builder event(BexValue event) {
            return binding("event", event);
        }

        public Builder processingEvent(BexValue processingEvent) {
            return binding("processingEvent", processingEvent);
        }

        public Builder currentContract(BexValue currentContract) {
            return binding("currentContract", currentContract);
        }

        public Builder steps(BexStepResults steps) {
            this.steps = steps;
            if (steps != null) {
                binding("steps", steps.asValue());
            }
            return this;
        }

        public Builder binding(String name, BexValue value) {
            validateBindingName(name);
            bindings.put(name, new EagerBindingDefinition(value));
            return this;
        }

        /**
         * Adds a host binding that is resolved when it is first read.
         *
         * <p>The supplier is invoked at most once for each built execution
         * context. A {@code null} result is exposed as
         * {@link BexValues#undefined()}. The standard {@code event},
         * {@code currentContract}, and {@code steps} bindings remain eager and
         * cannot be supplied lazily. Cyclic lazy-binding reads fail
         * deterministically and memoize the failure.</p>
         *
         * @param name binding name
         * @param supplier concrete value supplier
         * @return this builder
         * @throws IllegalArgumentException if the name is invalid or names a
         *                                  standard eager binding
         * @throws NullPointerException if {@code supplier} is {@code null}
         */
        public Builder lazyBinding(String name, Supplier<? extends BexValue> supplier) {
            validateBindingName(name);
            if (isStandardBindingName(name)) {
                throw new IllegalArgumentException("standard binding must remain eager: " + name);
            }
            bindings.put(name, new LazyBindingDefinition(Objects.requireNonNull(supplier, "supplier")));
            return this;
        }

        public Builder bindings(Map<String, BexValue> values) {
            if (values == null) {
                return this;
            }
            for (Map.Entry<String, BexValue> entry : values.entrySet()) {
                binding(entry.getKey(), entry.getValue());
            }
            return this;
        }

        public Builder gasLimit(long gasLimit) {
            if (gasLimit < BexGasMeter.NO_LOCAL_LIMIT) {
                throw new IllegalArgumentException(
                        "gasLimit must be non-negative or NO_LOCAL_LIMIT");
            }
            this.gasLimit = gasLimit;
            return this;
        }

        public Builder parentRemainingGas(long parentRemainingGas) {
            if (parentRemainingGas < 0L) {
                throw new IllegalArgumentException(
                        "parentRemainingGas must be non-negative");
            }
            this.parentRemainingGas = parentRemainingGas;
            return this;
        }

        public Builder gasLedgerHost(BexGasLedgerHost gasLedgerHost) {
            this.gasLedgerHost = gasLedgerHost;
            return this;
        }

        public Builder semanticIdentityBoundary(
                BexSemanticIdentityBoundary semanticIdentityBoundary) {
            this.semanticIdentityBoundary = semanticIdentityBoundary != null
                    ? semanticIdentityBoundary
                    : null;
            return this;
        }

        public Builder failureBoundary(BexFailureBoundary failureBoundary) {
            this.failureBoundary = failureBoundary;
            return this;
        }

        public BexExecutionContext build() {
            return new BexExecutionContext(this);
        }
    }

    private static void validateBindingName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("binding name is required");
        }
    }

    private static boolean isStandardBindingName(String name) {
        return "event".equals(name)
                || "processingEvent".equals(name)
                || "currentContract".equals(name)
                || "steps".equals(name);
    }

    private static BexValue bindingFrom(Map<String, BindingSlot> bindingSlots, String name) {
        BindingSlot slot = bindingSlots.get(name);
        return slot != null ? slot.resolve() : BexValues.undefined();
    }

    private interface BindingDefinition {
        BindingSlot createSlot(String name, ResolutionCoordinator resolutionCoordinator);
    }

    private interface BindingSlot {
        BexValue resolve();
    }

    private static final class EagerBindingDefinition implements BindingDefinition {
        private final BexValue value;

        private EagerBindingDefinition(BexValue value) {
            this.value = value != null ? value : BexValues.undefined();
        }

        @Override
        public BindingSlot createSlot(String name, ResolutionCoordinator resolutionCoordinator) {
            return new EagerBindingSlot(value);
        }
    }

    private static final class LazyBindingDefinition implements BindingDefinition {
        private final Supplier<? extends BexValue> supplier;

        private LazyBindingDefinition(Supplier<? extends BexValue> supplier) {
            this.supplier = supplier;
        }

        @Override
        public BindingSlot createSlot(String name, ResolutionCoordinator resolutionCoordinator) {
            return new LazyBindingSlot(name, supplier, resolutionCoordinator);
        }
    }

    private static final class EagerBindingSlot implements BindingSlot {
        private final BexValue value;

        private EagerBindingSlot(BexValue value) {
            this.value = value != null ? value : BexValues.undefined();
        }

        @Override
        public BexValue resolve() {
            return value;
        }
    }

    private static final class LazyBindingSlot implements BindingSlot {
        private final String name;
        private final Supplier<? extends BexValue> supplier;
        private final ResolutionCoordinator resolutionCoordinator;
        private volatile ResolutionState state = ResolutionState.UNRESOLVED;
        private Thread resolvingThread;
        private BexValue value;
        private Throwable failure;

        private LazyBindingSlot(String name,
                                Supplier<? extends BexValue> supplier,
                                ResolutionCoordinator resolutionCoordinator) {
            this.name = name;
            this.supplier = supplier;
            this.resolutionCoordinator = resolutionCoordinator;
        }

        @Override
        public BexValue resolve() {
            ResolutionState observed = state;
            if (observed == ResolutionState.RESOLVED) {
                return value;
            }
            if (observed == ResolutionState.FAILED) {
                return rethrow(failure);
            }

            ResolutionDependency dependency = resolutionCoordinator.registerDependency(this);
            if (dependency != null && dependency.cycleFailure != null) {
                return dependency.failResolutionCycle();
            }

            boolean interrupted = false;
            try {
                boolean resolveHere = false;
                synchronized (this) {
                    while (!resolveHere) {
                        observed = state;
                        if (observed == ResolutionState.RESOLVED) {
                            return value;
                        }
                        if (observed == ResolutionState.FAILED) {
                            return rethrow(failure);
                        }
                        if (observed == ResolutionState.UNRESOLVED) {
                            resolvingThread = Thread.currentThread();
                            state = ResolutionState.RESOLVING;
                            resolveHere = true;
                            continue;
                        }
                        if (resolvingThread == Thread.currentThread()) {
                            return failResolutionCycle(new IllegalStateException(
                                    "Lazy binding cycle: " + name + " -> " + name));
                        }
                        try {
                            wait();
                        } catch (InterruptedException ex) {
                            interrupted = true;
                        }
                    }
                }

                BexValue supplied;
                try {
                    resolutionCoordinator.enterSupplier(this);
                    supplied = supplier.get();
                } catch (Throwable ex) {
                    return rethrow(completeFailure(ex));
                } finally {
                    resolutionCoordinator.exitSupplier(this);
                }
                return completeSuccess(supplied);
            } finally {
                if (dependency != null) {
                    dependency.close();
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private BexValue completeSuccess(BexValue supplied) {
            synchronized (this) {
                if (state == ResolutionState.RESOLVING
                        && resolvingThread == Thread.currentThread()) {
                    value = supplied != null ? supplied : BexValues.undefined();
                    state = ResolutionState.RESOLVED;
                    resolvingThread = null;
                    notifyAll();
                    return value;
                }
                if (state == ResolutionState.FAILED) {
                    return rethrow(failure);
                }
                return rethrow(new IllegalStateException("Invalid lazy binding resolution state"));
            }
        }

        private Throwable completeFailure(Throwable thrown) {
            synchronized (this) {
                if (state == ResolutionState.RESOLVING
                        && resolvingThread == Thread.currentThread()) {
                    failure = thrown;
                    state = ResolutionState.FAILED;
                    resolvingThread = null;
                    notifyAll();
                    return thrown;
                }
                if (state == ResolutionState.FAILED) {
                    return failure;
                }
                return new IllegalStateException("Invalid lazy binding resolution state", thrown);
            }
        }

        private BexValue failResolutionCycle(IllegalStateException cycleFailure) {
            synchronized (this) {
                if (state == ResolutionState.RESOLVING
                        && resolvingThread == Thread.currentThread()) {
                    failure = cycleFailure;
                    state = ResolutionState.FAILED;
                    resolvingThread = null;
                    notifyAll();
                    return rethrow(cycleFailure);
                }
                if (state == ResolutionState.FAILED) {
                    return rethrow(failure);
                }
                return rethrow(new IllegalStateException("Invalid lazy binding resolution state", cycleFailure));
            }
        }

        private boolean needsResolution() {
            return state == ResolutionState.UNRESOLVED || state == ResolutionState.RESOLVING;
        }

        private static BexValue rethrow(Throwable thrown) {
            LazyBindingSlot.<RuntimeException>throwUnchecked(thrown);
            throw new AssertionError("unreachable");
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> void throwUnchecked(Throwable thrown) throws T {
            throw (T) thrown;
        }
    }

    private enum ResolutionState {
        UNRESOLVED,
        RESOLVING,
        RESOLVED,
        FAILED
    }

    private static final class ResolutionCoordinator {
        private final Object lock = new Object();
        private final ThreadLocal<Deque<LazyBindingSlot>> resolvingSlots = new ThreadLocal<Deque<LazyBindingSlot>>() {
            @Override
            protected Deque<LazyBindingSlot> initialValue() {
                return new ArrayDeque<>();
            }
        };
        private final Map<LazyBindingSlot, LazyBindingSlot> activeDependencies = new IdentityHashMap<>();

        private ResolutionDependency registerDependency(LazyBindingSlot target) {
            Deque<LazyBindingSlot> stack = resolvingSlots.get();
            LazyBindingSlot source = stack.peek();
            if (source == null) {
                resolvingSlots.remove();
                return null;
            }
            if (!target.needsResolution()) {
                return null;
            }

            synchronized (lock) {
                if (!target.needsResolution()) {
                    return null;
                }
                LazyBindingSlot cursor = target;
                while (cursor != null) {
                    if (cursor == source) {
                        return ResolutionDependency.cycle(source, cycleFailure(source, target, activeDependencies));
                    }
                    cursor = activeDependencies.get(cursor);
                }
                activeDependencies.put(source, target);
                return ResolutionDependency.active(this, source, target);
            }
        }

        private void enterSupplier(LazyBindingSlot slot) {
            resolvingSlots.get().push(slot);
        }

        private void exitSupplier(LazyBindingSlot slot) {
            Deque<LazyBindingSlot> stack = resolvingSlots.get();
            if (stack.isEmpty() || stack.pop() != slot) {
                stack.clear();
                resolvingSlots.remove();
                throw new IllegalStateException("Invalid lazy binding resolution stack");
            }
            if (stack.isEmpty()) {
                resolvingSlots.remove();
            }
        }

        private void removeDependency(LazyBindingSlot source, LazyBindingSlot target) {
            synchronized (lock) {
                if (activeDependencies.get(source) == target) {
                    activeDependencies.remove(source);
                }
            }
        }

        private static IllegalStateException cycleFailure(LazyBindingSlot source,
                                                          LazyBindingSlot target,
                                                          Map<LazyBindingSlot, LazyBindingSlot> dependencies) {
            StringBuilder message = new StringBuilder("Lazy binding cycle: ").append(source.name);
            LazyBindingSlot slot = target;
            while (slot != null) {
                message.append(" -> ").append(slot.name);
                slot = dependencies.get(slot);
            }
            return new IllegalStateException(message.toString());
        }
    }

    private static final class ResolutionDependency {
        private final ResolutionCoordinator resolutionCoordinator;
        private final LazyBindingSlot source;
        private final LazyBindingSlot target;
        private final IllegalStateException cycleFailure;

        private ResolutionDependency(ResolutionCoordinator resolutionCoordinator,
                                     LazyBindingSlot source,
                                     LazyBindingSlot target,
                                     IllegalStateException cycleFailure) {
            this.resolutionCoordinator = resolutionCoordinator;
            this.source = source;
            this.target = target;
            this.cycleFailure = cycleFailure;
        }

        private static ResolutionDependency active(ResolutionCoordinator resolutionCoordinator,
                                                    LazyBindingSlot source,
                                                    LazyBindingSlot target) {
            return new ResolutionDependency(resolutionCoordinator, source, target, null);
        }

        private static ResolutionDependency cycle(LazyBindingSlot source, IllegalStateException cycleFailure) {
            return new ResolutionDependency(null, source, null, cycleFailure);
        }

        private BexValue failResolutionCycle() {
            return source.failResolutionCycle(cycleFailure);
        }

        private void close() {
            resolutionCoordinator.removeDependency(source, target);
        }
    }
}
