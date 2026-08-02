package blue.bex.compile;

import blue.bex.BexSourcePath;
import blue.bex.gas.BexGasMeter;
import blue.bex.result.BexMetricsRecorder;
import blue.bex.result.BexPatchEntry;
import blue.bex.value.BexValue;
import blue.language.snapshot.FrozenNode;

import java.util.List;
import java.util.Map;

/**
 * Per-execution machine operations required by compiled BEX IR.
 *
 * <p>This port is intentionally owned by the compiler package: compiled nodes
 * can execute without depending on a concrete runtime implementation. A
 * machine is invocation-scoped and must not be shared between executions.</p>
 */
public interface BexExecutionMachine {
    BexCompiledProgram program();

    BexGasMeter gas();

    BexMetricsRecorder metrics();

    BexValue readDocument(
            String absolutePointer,
            List<String> precompiledSegments,
            boolean resolved);

    BexValue readEvent(List<String> precompiledSegments);

    BexValue readProcessingEvent(List<String> precompiledSegments);

    BexValue readCurrentContract(List<String> precompiledSegments);

    BexValue readBinding(String name, List<String> pathSegments);

    BexValue readSteps(String step, List<String> pathSegments);

    BexValue readResultValue(String absolutePointer, List<String> segments);

    BexValue readValuePointer(BexValue root, List<String> segments);

    BexValue defaultResultValue();

    BexValue invokeIntrinsic(
            String blueId,
            BexValue type,
            Map<String, BexValue> fields);

    BexValue nodeBlueId(BexValue value);

    boolean matchesType(
            BexValue value,
            FrozenNode pattern,
            BexSourcePath sourcePath);

    String resolvePointer(String authoredPointer);

    String canonicalPointer(String pointer);

    List<String> parseDynamicPointer(String pointer);

    void appendChange(BexPatchEntry entry);

    void appendEvent(BexValue event);

    BexValue changesetValue();

    BexValue eventsValue();
}
