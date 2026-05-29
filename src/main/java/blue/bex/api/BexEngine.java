package blue.bex.api;

import blue.bex.BexException;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.compile.BexCompiledProgramCache;
import blue.bex.compile.BexCompiledProgramKey;
import blue.bex.compile.BexCompiler;
import blue.bex.compile.LruBexCompiledProgramCache;
import blue.bex.gas.BexGasSchedule;
import blue.bex.pointer.BexPointerCache;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetrics;
import blue.bex.runtime.BexRuntime;
import blue.language.Blue;

/**
 * Public entry point for compiling and executing selected BEX programs.
 *
 * <p>The engine is compiled-only. It compiles a {@link BexProgramSource} after a
 * host has selected a BEX program, caches the compiled form, and executes it
 * against a {@link BexExecutionContext}. It does not apply document patches,
 * emit events, or perform host actions.</p>
 */
public final class BexEngine {
    private final Blue blue;
    private final BexGasSchedule gasSchedule;
    private final BexCompiledProgramCache cache;
    private final BexMetricsSink metricsSink;
    private final BexIntrinsicRegistry intrinsics;
    private final BexPointerCache pointerCache = new BexPointerCache();

    private BexEngine(Builder builder) {
        this.blue = builder.blue;
        this.gasSchedule = builder.gasSchedule;
        this.cache = builder.cache;
        this.metricsSink = builder.metricsSink;
        this.intrinsics = builder.intrinsics;
    }

    public static Builder builder() {
        return new Builder();
    }

    public BexCompiledProgram compile(BexProgramSource source) {
        BexMetrics metrics = new BexMetrics();
        BexCompiledProgram program = compile(source, metrics);
        metricsSink.accept(metrics);
        return program;
    }

    private BexCompiledProgram compile(BexProgramSource source, BexMetrics metrics) {
        long start = System.nanoTime();
        try {
        BexCompiledProgramKey key = key(source);
        BexCompiledProgram cached = cache.get(key);
        if (cached != null) {
            metrics.incrementCompileCacheHits();
            validateIntrinsicSupport(cached);
            return cached;
        }
        metrics.incrementCompileCacheMisses();
        BexCompiledProgram program = new BexCompiler(metrics, intrinsics).compile(source);
        validateIntrinsicSupport(program);
        cache.put(key, program);
        return program;
        } finally {
            metrics.addCompileNanos(System.nanoTime() - start);
        }
    }

    public BexExecutionResult execute(BexCompiledProgram program, BexExecutionContext context) {
        BexMetrics metrics = new BexMetrics();
        BexExecutionResult result = execute(program, context, metrics);
        metricsSink.accept(result.metrics());
        return result;
    }

    private BexExecutionResult execute(BexCompiledProgram program, BexExecutionContext context, BexMetrics metrics) {
        long start = System.nanoTime();
        validateIntrinsicSupport(program);
        BexRuntime runtime = new BexRuntime(program, context, blue, gasSchedule, metrics, pointerCache, intrinsics);
        BexExecutionResult result = runtime.execute();
        metrics.addExecuteNanos(System.nanoTime() - start);
        return new BexExecutionResult(result.value(),
                result.changeset(),
                result.events(),
                result.gasUsed(),
                metrics);
    }

    public BexExecutionResult compileAndExecute(BexProgramSource source, BexExecutionContext context) {
        BexMetrics metrics = new BexMetrics();
        BexCompiledProgram program = compile(source, metrics);
        BexExecutionResult result = execute(program, context, metrics);
        metricsSink.accept(result.metrics());
        return result;
    }

    private BexCompiledProgramKey key(BexProgramSource source) {
        return BexCompiledProgramKey.from(source);
    }

    private void validateIntrinsicSupport(BexCompiledProgram program) {
        for (String blueId : program.requiredIntrinsicBlueIds()) {
            if (!intrinsics.supports(blueId)) {
                throw new BexException("Unsupported intrinsic BlueId: " + blueId);
            }
        }
    }

    public static final class Builder {
        private Blue blue = new Blue();
        private BexGasSchedule gasSchedule = BexGasSchedule.defaults();
        private BexCompiledProgramCache cache = new LruBexCompiledProgramCache();
        private BexMetricsSink metricsSink = BexMetricsSink.NOOP;
        private BexIntrinsicRegistry intrinsics = BexIntrinsicRegistry.empty();

        public Builder blue(Blue blue) {
            this.blue = blue != null ? blue : new Blue();
            return this;
        }

        public Builder gasSchedule(BexGasSchedule gasSchedule) {
            this.gasSchedule = gasSchedule;
            return this;
        }

        public Builder cache(BexCompiledProgramCache cache) {
            this.cache = cache;
            return this;
        }

        public Builder metrics(BexMetricsSink metrics) {
            this.metricsSink = metrics != null ? metrics : BexMetricsSink.NOOP;
            return this;
        }

        public Builder intrinsics(BexIntrinsicRegistry intrinsics) {
            this.intrinsics = intrinsics != null ? intrinsics : BexIntrinsicRegistry.empty();
            return this;
        }

        public Builder intrinsic(String blueId, BexIntrinsicProcessor processor) {
            this.intrinsics = this.intrinsics.with(blueId, processor);
            return this;
        }

        public Builder intrinsic(Class<?> typeClass, BexIntrinsicProcessor processor) {
            this.intrinsics = this.intrinsics.with(typeClass, processor);
            return this;
        }

        public BexEngine build() {
            return new BexEngine(this);
        }
    }
}
