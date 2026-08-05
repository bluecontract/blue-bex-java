package blue.bex.gas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Mutable per-run trace recorder with immutable snapshot views. */
final class BexGasTraceRecorder {
    private final BexGasSchedule schedule;
    private final List<BexGasCharge> trace = new ArrayList<>();
    private long totalGas;

    BexGasTraceRecorder(BexGasSchedule schedule) {
        this.schedule = schedule;
    }

    long totalGas() {
        return totalGas;
    }

    List<BexGasCharge> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(trace));
    }

    BexGasLedger ledger() {
        return new BexGasLedger(
                trace,
                schedule.scheduleId(),
                schedule.manifestIdentity());
    }

    void append(
            String namespace,
            String counterName,
            long quantity,
            long weight,
            long gas,
            String sourcePath,
            String operator,
            String reason) {
        trace.add(new BexGasCharge(
                trace.size(),
                namespace,
                counterName,
                quantity,
                weight,
                gas,
                sourcePath,
                operator,
                reason));
        totalGas += gas;
    }
}
