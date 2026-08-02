package blue.bex.gas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable portable and intrinsic named-counter catalog. */
final class BexGasCounterCatalog {
    static final class Weight {
        final String namespace;
        final String counterName;
        final BexGasCounter portableCounter;
        final long weight;

        private Weight(
                String namespace,
                String counterName,
                BexGasCounter portableCounter,
                long weight) {
            this.namespace = namespace;
            this.counterName = counterName;
            this.portableCounter = portableCounter;
            this.weight = weight;
        }
    }

    private final BexGasSchedule schedule;
    private final Map<String, Long> registeredWeights;

    BexGasCounterCatalog(
            BexGasSchedule schedule,
            Map<String, Long> registeredWeights) {
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.registeredWeights = immutableWeights(registeredWeights);
    }

    BexGasSchedule schedule() {
        return schedule;
    }

    Map<String, Long> registeredWeights() {
        return registeredWeights;
    }

    Map<String, Long> combinedWeights() {
        return combinedWeights(schedule, registeredWeights);
    }

    Weight weight(String namespace, String counterName) {
        String exactNamespace = requireName(namespace, "Gas namespace");
        String exactCounterName = requireName(counterName, "Gas counter");
        if (BexGasCounter.NAMESPACE.equals(exactNamespace)) {
            BexGasCounter portable =
                    BexGasCounter.fromCanonicalName(exactCounterName);
            return new Weight(
                    exactNamespace,
                    exactCounterName,
                    portable,
                    schedule.weight(portable));
        }
        String qualified = qualifiedName(
                exactNamespace, exactCounterName);
        Long weight = registeredWeights.get(qualified);
        if (weight == null) {
            throw new IllegalArgumentException(
                    "Unregistered named gas counter: " + qualified);
        }
        return new Weight(
                exactNamespace,
                exactCounterName,
                null,
                weight);
    }

    static String qualifiedName(String namespace, String counterName) {
        String exactNamespace = requireName(namespace, "Gas namespace");
        String exactCounter = requireName(counterName, "Gas counter");
        return BexGasCounter.NAMESPACE.equals(exactNamespace)
                ? exactCounter
                : exactNamespace + "." + exactCounter;
    }

    static Map<String, Long> combinedWeights(
            BexGasSchedule schedule,
            Map<String, Long> registeredWeights) {
        LinkedHashMap<String, Long> combined = new LinkedHashMap<>(
                Objects.requireNonNull(schedule, "schedule").counterWeights());
        Map<String, Long> registered = immutableWeights(registeredWeights);
        for (Map.Entry<String, Long> entry : registered.entrySet()) {
            if (combined.containsKey(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Registered gas counter collides with BEX manifest counter: "
                                + entry.getKey());
            }
            combined.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(combined);
    }

    private static Map<String, Long> immutableWeights(
            Map<String, Long> registeredWeights) {
        if (registeredWeights == null || registeredWeights.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<String, Long> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : registeredWeights.entrySet()) {
            String counterName = requireName(
                    entry.getKey(), "Registered gas counter");
            Long weight = Objects.requireNonNull(
                    entry.getValue(), "Registered gas weight");
            if (weight <= 0L) {
                throw new IllegalArgumentException(
                        "Registered gas weight must be positive");
            }
            copy.put(counterName, weight);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static String requireName(String value, String label) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
