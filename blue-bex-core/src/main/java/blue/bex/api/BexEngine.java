package blue.bex.api;

import blue.bex.BexException;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.compile.BexCompiledProgramCache;
import blue.bex.compile.BexCompiledProgramKey;
import blue.bex.compile.BexCompiledProgramRuntimeAccess;
import blue.bex.compile.BexCompilerRuntimeAccess;
import blue.bex.compile.LruBexCompiledProgramCache;
import blue.bex.gas.BexGasSchedule;
import blue.bex.pointer.BexPointerCache;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetricsRecorder;
import blue.bex.result.BexMetricsSnapshot;
import blue.bex.runtime.BexRuntime;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.runtime.BlueLanguage;

import java.util.Map;
import java.util.Objects;

/**
 * Public entry point for compiling and executing selected BEX programs.
 *
 * <p>The engine is compiled-only. It compiles a {@link BexProgramSource} after a
 * host has selected a BEX program, caches the compiled form, and executes it
 * against a {@link BexExecutionContext}. It does not apply document patches,
 * emit events, or perform host actions.</p>
 */
public final class BexEngine implements AutoCloseable {
    private final BlueLanguage blue;
    private final boolean ownsBlue;
    private final BexGasSchedule gasSchedule;
    private final BexCompiledProgramCache cache;
    private final BexMetricsSink metricsSink;
    private final BexIntrinsicRegistry intrinsics;
    private final BexPointerCache pointerCache = new BexPointerCache();

    private BexEngine(Builder builder) {
        this.ownsBlue = builder.blue == null;
        this.blue = ownsBlue
                ? BlueLanguage.builder().build()
                : builder.blue;
        this.gasSchedule = builder.gasSchedule;
        this.cache = builder.cache;
        this.metricsSink = builder.metricsSink;
        this.intrinsics = builder.intrinsics;
    }

    public static Builder builder() {
        return new Builder();
    }

    public BexCompiledProgram compile(BexProgramSource source) {
        BexMetricsRecorder metrics = new BexMetricsRecorder();
        BexCompiledProgram program = compile(source, metrics);
        publishMetrics(metrics.snapshot());
        return program;
    }

    private BexCompiledProgram compile(
            BexProgramSource source,
            BexMetricsRecorder metrics) {
        long start = System.nanoTime();
        try {
        BexCompiledProgramKey key = key(source);
        BexCompiledProgram cached = cache.get(key);
        if (cached != null) {
            metrics.incrementCompileCacheHits();
            validateCompilationKey(cached, key);
            validateIntrinsicSupport(cached);
            return cached;
        }
        metrics.incrementCompileCacheMisses();
        BexCompiledProgram program = BexCompilerRuntimeAccess.compile(
                source, metrics, intrinsics, compileEnvironmentIdentity());
        validateCompilationKey(program, key);
        validateIntrinsicSupport(program);
        cache.put(key, program);
        return program;
        } finally {
            metrics.addCompileNanos(System.nanoTime() - start);
        }
    }

    public BexExecutionResult execute(BexCompiledProgram program, BexExecutionContext context) {
        BexMetricsRecorder metrics = new BexMetricsRecorder();
        BexExecutionResult result = execute(program, context, metrics);
        publishMetrics(result.metricsSnapshot());
        return result;
    }

    private BexExecutionResult execute(
            BexCompiledProgram program,
            BexExecutionContext context,
            BexMetricsRecorder metrics) {
        long start = System.nanoTime();
        validateCompilationEnvironment(program);
        validateIntrinsicSupport(program);
        BexRuntime runtime = new BexRuntime(program, context, blue, gasSchedule, metrics, pointerCache, intrinsics);
        BexExecutionResult result = runtime.execute();
        metrics.addExecuteNanos(System.nanoTime() - start);
        return new BexExecutionResult(result.value(),
                result.changeset(),
                result.events(),
                result.gasLedger(),
                metrics.snapshot(),
                result.output());
    }

    public BexExecutionResult compileAndExecute(BexProgramSource source, BexExecutionContext context) {
        BexMetricsRecorder metrics = new BexMetricsRecorder();
        BexCompiledProgram program = compile(source, metrics);
        BexExecutionResult result = execute(program, context, metrics);
        publishMetrics(result.metricsSnapshot());
        return result;
    }

    private BexCompiledProgramKey key(BexProgramSource source) {
        return BexCompiledProgramKey.from(source, compileEnvironmentIdentity());
    }

    private String compileEnvironmentIdentity() {
        return BexCompiledProgramKey.COMPILER_IDENTITY
                + "|runtimeRegistry="
                + BexCompiledProgramKey.BEX_RUNTIME_REGISTRY_IDENTITY
                + "|gasManifest=" + gasSchedule.manifestIdentity()
                + "|gasWeights=" + gasSchedule.counterWeights()
                + "|languageRegistry="
                + BlueCoreTypeRegistry.INSTANCE.packageIdentity()
                + "|intrinsics=" + intrinsics.identity();
    }

    private void validateIntrinsicSupport(BexCompiledProgram program) {
        for (String blueId : program.requiredIntrinsicBlueIds()) {
            if (!intrinsics.supports(blueId)) {
                throw new BexException("Unsupported intrinsic BlueId: " + blueId);
            }
        }
    }

    private void validateCompilationEnvironment(BexCompiledProgram program) {
        String expected = compileEnvironmentIdentity();
        if (!expected.equals(program.compilationEnvironmentIdentity())) {
            throw new BexException(
                    "Compiled BEX environment identity mismatch: expected "
                            + expected + " but program was compiled with "
                            + program.compilationEnvironmentIdentity());
        }
    }

    private void validateCompilationKey(
            BexCompiledProgram program,
            BexCompiledProgramKey expected) {
        if (!BexCompiledProgramRuntimeAccess.matchesCompilationKey(
                program, expected)) {
            throw new BexException(
                    "Compiled BEX cache key does not match the requested "
                            + "program, definition, entry, source kind, and environment");
        }
    }

    private void publishMetrics(BexMetricsSnapshot metrics) {
        try {
            metricsSink.accept(metrics);
        } catch (RuntimeException ignored) {
            // Diagnostics must never alter compilation, execution, gas, or cache semantics.
        }
    }

    /** Closes only the default Language runtime created and owned by this engine. */
    @Override
    public void close() {
        if (ownsBlue) {
            blue.close();
        }
    }

    public static final class Builder {
        private BlueLanguage blue;
        private BexGasSchedule gasSchedule = BexGasSchedule.defaults();
        private BexCompiledProgramCache cache = new LruBexCompiledProgramCache();
        private BexMetricsSink metricsSink = BexMetricsSink.NOOP;
        private BexIntrinsicRegistry intrinsics = BexIntrinsicRegistry.empty();

        public Builder language(BlueLanguage blue) {
            this.blue = blue;
            return this;
        }

        public Builder gasSchedule(BexGasSchedule gasSchedule) {
            this.gasSchedule = Objects.requireNonNull(
                    gasSchedule, "gasSchedule");
            return this;
        }

        public Builder cache(BexCompiledProgramCache cache) {
            this.cache = Objects.requireNonNull(cache, "cache");
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

        public Builder intrinsic(String blueId,
                                 String registryIdentity,
                                 Map<String, Long> counterWeights,
                                 BexIntrinsicProcessor processor) {
            this.intrinsics = this.intrinsics.with(
                    blueId, registryIdentity, counterWeights, processor);
            return this;
        }

        public Builder intrinsic(Class<?> typeClass,
                                 String registryIdentity,
                                 Map<String, Long> counterWeights,
                                 BexIntrinsicProcessor processor) {
            this.intrinsics = this.intrinsics.with(
                    typeClass,
                    registryIdentity,
                    counterWeights,
                    processor);
            return this;
        }

        public Builder intrinsic(Class<?> typeClass,
                                 BexTypeBlueIdResolver typeBlueIdResolver,
                                 String registryIdentity,
                                 Map<String, Long> counterWeights,
                                 BexIntrinsicProcessor processor) {
            this.intrinsics = this.intrinsics.with(
                    typeClass,
                    typeBlueIdResolver,
                    registryIdentity,
                    counterWeights,
                    processor);
            return this;
        }

        public BexEngine build() {
            return new BexEngine(this);
        }
    }
}
