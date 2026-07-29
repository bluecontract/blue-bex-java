package blue.bex.conformance;

import blue.bex.gas.BexGasLimitExceededException;
import blue.bex.output.BexAdmittedValue;
import blue.bex.result.BexExecutionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase-aware, engine-neutral observation of one fixture execution.
 */
final class BexFixtureRun {
    final String name;
    final boolean runtimeStarted;
    final BexExecutionResult executionResult;
    final Throwable failure;
    final String errorClass;
    final String diagnosticSourcePath;
    final String diagnosticOperator;
    final List<String> demands;
    final String providerBatching;
    final int providerWarmupNodeLoads;
    final int providerWarmupBatchLoads;
    final int providerRuntimeNodeLoads;
    final int providerRuntimeBatchLoads;
    final int providerRuntimeCacheHits;
    final List<Map<String, Object>> gasTrace;
    final long gasTotal;
    final long effectiveRuntimeBudget;
    final long parentBudgetBefore;
    final long parentBudgetAfter;
    final int hostOpenCount;
    final int hostMergeCount;
    final boolean hostLiveBounded;
    final boolean failedChargePresent;
    final long semanticIdentityMergeCount;
    final boolean bufferedEffectsCommitted;
    final Object result;
    final Object changes;
    final Object events;

    BexFixtureRun(String name,
                  boolean runtimeStarted,
                  BexExecutionResult executionResult,
                  Throwable failure,
                  String errorClass,
                  String diagnosticSourcePath,
                  String diagnosticOperator,
                  List<String> demands,
                  String providerBatching,
                  int providerWarmupNodeLoads,
                  int providerWarmupBatchLoads,
                  int providerRuntimeNodeLoads,
                  int providerRuntimeBatchLoads,
                  int providerRuntimeCacheHits,
                  List<Map<String, Object>> gasTrace,
                  long gasTotal,
                  long effectiveRuntimeBudget,
                  long parentBudgetBefore,
                  long parentBudgetAfter,
                  int hostOpenCount,
                  int hostMergeCount,
                  boolean hostLiveBounded,
                  boolean failedChargePresent,
                  long semanticIdentityMergeCount,
                  boolean bufferedEffectsCommitted,
                  Object result,
                  Object changes,
                  Object events) {
        this.name = name;
        this.runtimeStarted = runtimeStarted;
        this.executionResult = executionResult;
        this.failure = failure;
        this.errorClass = errorClass;
        this.diagnosticSourcePath = diagnosticSourcePath;
        this.diagnosticOperator = diagnosticOperator;
        this.demands = immutableStrings(demands);
        this.providerBatching = providerBatching;
        this.providerWarmupNodeLoads = providerWarmupNodeLoads;
        this.providerWarmupBatchLoads = providerWarmupBatchLoads;
        this.providerRuntimeNodeLoads = providerRuntimeNodeLoads;
        this.providerRuntimeBatchLoads = providerRuntimeBatchLoads;
        this.providerRuntimeCacheHits = providerRuntimeCacheHits;
        this.gasTrace = immutableTrace(gasTrace);
        this.gasTotal = gasTotal;
        this.effectiveRuntimeBudget = effectiveRuntimeBudget;
        this.parentBudgetBefore = parentBudgetBefore;
        this.parentBudgetAfter = parentBudgetAfter;
        this.hostOpenCount = hostOpenCount;
        this.hostMergeCount = hostMergeCount;
        this.hostLiveBounded = hostLiveBounded;
        this.failedChargePresent = failedChargePresent;
        this.semanticIdentityMergeCount = semanticIdentityMergeCount;
        this.bufferedEffectsCommitted = bufferedEffectsCommitted;
        this.result = result;
        this.changes = changes;
        this.events = events;
    }

    long gasQuantity(String counter) {
        long quantity = 0L;
        for (Map<String, Object> charge : gasTrace) {
            if (counter.equals(charge.get("counter"))) {
                quantity += ((Number) charge.get("quantity")).longValue();
            }
        }
        return quantity;
    }

    boolean hasGasCounter(String counter) {
        for (Map<String, Object> charge : gasTrace) {
            if (counter.equals(charge.get("counter"))) {
                return true;
            }
        }
        return false;
    }

    boolean hasNamedIntrinsicCharge() {
        for (Map<String, Object> charge : gasTrace) {
            Object namespace = charge.get("namespace");
            if (namespace != null && !"bex".equals(namespace)) {
                return true;
            }
            Object counter = charge.get("counter");
            if (counter != null
                    && String.valueOf(counter).startsWith("intrinsic.")) {
                return true;
            }
        }
        return false;
    }

    boolean hasOpaqueIntrinsicGas() {
        for (Map<String, Object> charge : gasTrace) {
            String counter = String.valueOf(charge.get("counter"));
            if ("opaqueGas".equals(counter)
                    || "gasUsed".equals(counter)
                    || "intrinsicGas".equals(counter)) {
                return true;
            }
        }
        return false;
    }

    BexAdmittedValue output() {
        return executionResult != null ? executionResult.output() : null;
    }

    BexGasLimitExceededException gasFailure() {
        return failure instanceof BexGasLimitExceededException
                ? (BexGasLimitExceededException) failure
                : null;
    }

    private static List<String> immutableStrings(List<String> source) {
        return Collections.unmodifiableList(new ArrayList<String>(source));
    }

    private static List<Map<String, Object>> immutableTrace(
            List<Map<String, Object>> source) {
        List<Map<String, Object>> copy =
                new ArrayList<Map<String, Object>>(source.size());
        for (Map<String, Object> entry : source) {
            copy.add(Collections.unmodifiableMap(
                    new LinkedHashMap<String, Object>(entry)));
        }
        return Collections.unmodifiableList(copy);
    }
}
