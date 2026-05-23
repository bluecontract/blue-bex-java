package blue.bex.runtime;

import blue.bex.api.BexExecutionContext;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.gas.BexGasMeter;
import blue.bex.gas.BexGasSchedule;
import blue.bex.pointer.BexPointerCache;
import blue.bex.result.BexChangeset;
import blue.bex.result.BexExecutionResult;
import blue.bex.result.BexMetrics;
import blue.bex.result.BexResultOverlay;
import blue.bex.type.BexBlueTypeMatcher;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.Blue;
import blue.language.utils.JsonPointer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime for one compiled BEX execution.
 */
public final class BexRuntime {
    private final BexCompiledProgram program;
    private final BexExecutionContext context;
    private final BexGasMeter gas;
    private final BexMetrics metrics;
    private final BexPointerCache pointerCache;
    private final BexExecutionAccumulator accumulator;
    private final BexBlueTypeMatcher typeMatcher;

    public BexRuntime(BexCompiledProgram program,
                      BexExecutionContext context,
                      Blue blue,
                      BexGasSchedule gasSchedule,
                      BexMetrics metrics,
                      BexPointerCache pointerCache) {
        this.program = program;
        this.context = context;
        this.gas = new BexGasMeter(gasSchedule, context.gasLimit(), metrics);
        this.metrics = metrics;
        this.pointerCache = pointerCache;
        this.accumulator = new BexExecutionAccumulator(new BexResultOverlay(context.document(), metrics));
        this.typeMatcher = new BexBlueTypeMatcher(blue);
    }

    public BexExecutionResult execute() {
        metrics.incrementCompiledExecutions();
        BexValue value = program.execute(this);
        return new BexExecutionResult(value, accumulator.changeset(), accumulator.events(), gas.used(), metrics);
    }

    public BexCompiledProgram program() { return program; }
    public BexExecutionContext context() { return context; }
    public BexGasMeter gas() { return gas; }
    public BexMetrics metrics() { return metrics; }
    public BexPointerCache pointerCache() { return pointerCache; }
    public BexExecutionAccumulator accumulator() { return accumulator; }
    public BexBlueTypeMatcher typeMatcher() { return typeMatcher; }

    public BexValue readDocument(String absolutePointer, List<String> precompiledSegments, boolean resolved) {
        if (resolved) {
            metrics.incrementResolvedDocumentReads();
            gas.charge(gas.schedule().documentRead);
            return context.document().resolvedAt(absolutePointer);
        }
        metrics.incrementFrozenDocumentReads();
        gas.charge(gas.schedule().documentRead);
        return context.document().canonicalAt(absolutePointer);
    }

    public BexValue readEvent(List<String> precompiledSegments) {
        metrics.incrementEventReads();
        gas.charge(gas.schedule().eventRead);
        return context.event().at(precompiledSegments);
    }

    public BexValue readCurrentContract(List<String> precompiledSegments) {
        metrics.incrementCurrentContractReads();
        gas.charge(gas.schedule().currentContractRead);
        return context.currentContract().at(precompiledSegments);
    }

    public BexValue readBinding(String name, List<String> pathSegments) {
        gas.charge(gas.schedule().varRead);
        if (name == null || name.isEmpty()) {
            return BexValues.undefined();
        }
        return context.binding(name).at(pathSegments);
    }

    public BexValue readSteps(String step, List<String> pathSegments) {
        metrics.incrementStepsReads();
        gas.charge(gas.schedule().stepsRead);
        return context.steps().step(step).at(pathSegments);
    }

    public BexValue readResultValue(String absolutePointer, List<String> segments) {
        gas.charge(gas.schedule().resultValueRead);
        return accumulator.overlay().valueAt(absolutePointer, segments);
    }

    public BexValue defaultResultValue() {
        Map<String, BexValue> result = new LinkedHashMap<>();
        BexChangeset changeset = accumulator.changeset();
        result.put("changeset", changeset.asValue());
        result.put("events", accumulator.events().asValue());
        return BexValues.map(result);
    }

    public String resolvePointer(String authoredPointer) {
        return context.document().resolvePointer(authoredPointer);
    }

    public List<String> parseDynamicPointer(String pointer) {
        return pointerCache.get(pointer, metrics).segments();
    }

    public String canonicalPointer(String pointer) {
        return JsonPointer.canonicalize(pointer);
    }
}
