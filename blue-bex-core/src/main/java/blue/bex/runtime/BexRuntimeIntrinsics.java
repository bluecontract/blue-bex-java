package blue.bex.runtime;

import blue.bex.BexException;
import blue.bex.gas.BexGasMeter;
import blue.bex.output.BexOutputAdmission;
import blue.bex.value.BexValue;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** Runtime-only intrinsic catalog and invocation boundary. */
public interface BexRuntimeIntrinsics {
    BexRuntimeIntrinsics EMPTY = new BexRuntimeIntrinsics() {
        @Override
        public Map<String, Long> registeredNamedWeights(
                Set<String> requiredBlueIds) {
            return Collections.emptyMap();
        }

        @Override
        public Map<String, Map<String, Long>> registeredNamespaceWeights(
                Set<String> requiredBlueIds) {
            return Collections.emptyMap();
        }

        @Override
        public BexValue invoke(
                String blueId,
                BexValue type,
                Map<String, BexValue> fields,
                BexGasMeter gas,
                BexOutputAdmission outputAdmission) {
            throw new BexException("Unsupported intrinsic BlueId: " + blueId);
        }
    };

    Map<String, Long> registeredNamedWeights(Set<String> requiredBlueIds);

    Map<String, Map<String, Long>> registeredNamespaceWeights(
            Set<String> requiredBlueIds);

    BexValue invoke(
            String blueId,
            BexValue type,
            Map<String, BexValue> fields,
            BexGasMeter gas,
            BexOutputAdmission outputAdmission);
}
