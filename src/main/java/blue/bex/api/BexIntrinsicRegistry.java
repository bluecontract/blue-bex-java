package blue.bex.api;

import blue.bex.BexException;
import blue.bex.gas.BexGasMeter;
import blue.bex.output.BexOutputAdmission;
import blue.bex.output.BexOutputKind;
import blue.bex.value.BexUnicodeOrder;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.utils.BlueIdResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exact intrinsic registry keyed by static Blue type identity.
 */
public final class BexIntrinsicRegistry {
    private static final BexIntrinsicRegistry EMPTY =
            new BexIntrinsicRegistry(Collections.<String, Registration>emptyMap());

    private final Map<String, Registration> registrations;
    private final Map<String, Long> registeredNamedWeights;
    private final Map<String, Map<String, Long>>
            registeredNamespaceWeights;
    private final String identity;

    private BexIntrinsicRegistry(Map<String, Registration> registrations) {
        this.registrations = Collections.unmodifiableMap(
                new LinkedHashMap<>(registrations));
        LinkedHashMap<String, Long> weights = new LinkedHashMap<>();
        LinkedHashMap<String, LinkedHashMap<String, Long>>
                weightsByNamespace = new LinkedHashMap<>();
        StringBuilder identityBuilder =
                new StringBuilder("blue-bex/intrinsics/2.0;");
        identityBuilder.append(registrations.size()).append(';');
        for (String blueId : BexUnicodeOrder.sortedCopy(registrations.keySet())) {
            Registration registration = registrations.get(blueId);
            appendIdentityToken(identityBuilder, blueId);
            appendIdentityToken(
                    identityBuilder, registration.registryIdentity);
            appendIdentityToken(
                    identityBuilder, registration.namespace);
            identityBuilder.append(
                    registration.counterWeights.size()).append(';');
            for (Map.Entry<String, Long> counter
                    : registration.counterWeights.entrySet()) {
                appendIdentityToken(
                        identityBuilder, counter.getKey());
                identityBuilder.append(
                        counter.getValue()).append(';');
            }
            for (Map.Entry<String, Long> counter
                    : registration.counterWeights.entrySet()) {
                LinkedHashMap<String, Long> namespaceWeights =
                        weightsByNamespace.get(registration.namespace);
                if (namespaceWeights == null) {
                    namespaceWeights = new LinkedHashMap<>();
                    weightsByNamespace.put(
                            registration.namespace, namespaceWeights);
                }
                String qualified = BexGasMeter.qualifiedCounterName(
                        registration.namespace, counter.getKey());
                if (weights.put(qualified, counter.getValue()) != null
                        || namespaceWeights.put(
                        counter.getKey(), counter.getValue()) != null) {
                    throw new IllegalArgumentException(
                            "Duplicate registered intrinsic counter " + qualified);
                }
            }
        }
        this.identity = identityBuilder.toString();
        this.registeredNamedWeights = Collections.unmodifiableMap(weights);
        LinkedHashMap<String, Map<String, Long>> immutableNamespaces =
                new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, Long>> entry
                : weightsByNamespace.entrySet()) {
            immutableNamespaces.put(
                    entry.getKey(),
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(entry.getValue())));
        }
        this.registeredNamespaceWeights =
                Collections.unmodifiableMap(immutableNamespaces);
    }

    public static BexIntrinsicRegistry empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public BexIntrinsicRegistry with(String blueId,
                                     String registryIdentity,
                                     Map<String, Long> counterWeights,
                                     BexIntrinsicProcessor processor) {
        return toBuilder()
                .register(blueId, registryIdentity, counterWeights, processor)
                .build();
    }

    public BexIntrinsicRegistry with(Class<?> typeClass,
                                     String registryIdentity,
                                     Map<String, Long> counterWeights,
                                     BexIntrinsicProcessor processor) {
        return toBuilder()
                .register(
                        typeClass,
                        registryIdentity,
                        counterWeights,
                        processor)
                .build();
    }

    public boolean supports(String blueId) {
        return registrations.containsKey(blueId);
    }

    public Set<String> supportedBlueIds() {
        return registrations.keySet();
    }

    public String identity() {
        return identity;
    }

    /**
     * Namespace-qualified weights to register in the live parent child ledger.
     */
    public Map<String, Long> registeredNamedWeights() {
        return registeredNamedWeights;
    }

    /**
     * Exact immutable counter catalogs grouped by their intrinsic runtime
     * namespace.  Hosted BEX uses these catalogs to open separate physical
     * child ledgers instead of flattening intrinsic counters into {@code bex}.
     */
    public Map<String, Map<String, Long>> registeredNamespaceWeights() {
        return registeredNamespaceWeights;
    }

    /**
     * Namespace-qualified weights needed by one statically compiled program.
     */
    public Map<String, Long> registeredNamedWeights(
            Set<String> requiredBlueIds) {
        LinkedHashMap<String, Long> selected =
                new LinkedHashMap<>();
        for (String blueId : BexUnicodeOrder.sortedCopy(
                requiredBlueIds != null
                        ? requiredBlueIds
                        : Collections.<String>emptySet())) {
            Registration registration = requiredRegistration(blueId);
            for (Map.Entry<String, Long> counter
                    : registration.counterWeights.entrySet()) {
                selected.put(
                        BexGasMeter.qualifiedCounterName(
                                registration.namespace,
                                counter.getKey()),
                        counter.getValue());
            }
        }
        return Collections.unmodifiableMap(selected);
    }

    /**
     * Physical runtime catalogs needed by one statically compiled program.
     */
    public Map<String, Map<String, Long>> registeredNamespaceWeights(
            Set<String> requiredBlueIds) {
        LinkedHashMap<String, LinkedHashMap<String, Long>> selected =
                new LinkedHashMap<>();
        for (String blueId : BexUnicodeOrder.sortedCopy(
                requiredBlueIds != null
                        ? requiredBlueIds
                        : Collections.<String>emptySet())) {
            Registration registration = requiredRegistration(blueId);
            LinkedHashMap<String, Long> namespace =
                    selected.get(registration.namespace);
            if (namespace == null) {
                namespace = new LinkedHashMap<>();
                selected.put(registration.namespace, namespace);
            }
            namespace.putAll(registration.counterWeights);
        }
        LinkedHashMap<String, Map<String, Long>> immutable =
                new LinkedHashMap<>();
        for (Map.Entry<String, LinkedHashMap<String, Long>> entry
                : selected.entrySet()) {
            immutable.put(
                    entry.getKey(),
                    Collections.unmodifiableMap(
                            new LinkedHashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(immutable);
    }

    public BexValue invoke(String blueId,
                           BexValue type,
                           Map<String, BexValue> fields,
                           BexGasMeter gas,
                           BexOutputAdmission outputAdmission) {
        Registration registration = registrations.get(blueId);
        if (registration == null) {
            throw new BexException("Unsupported intrinsic BlueId: " + blueId);
        }
        BexIntrinsicInvocation invocation = new BexIntrinsicInvocation(
                blueId,
                type,
                fields,
                registration.namespace,
                registration.counterWeights,
                (counter, quantity, reason) -> gas.chargeNamed(
                        registration.namespace,
                        counter,
                        quantity,
                        (String) null,
                        "$intrinsic",
                        reason),
                gas::used,
                value -> outputAdmission.admit(
                        value, BexOutputKind.INTRINSIC_INPUT));
        BexValue value = registration.processor.execute(invocation);
        return value != null ? value : BexValues.undefined();
    }

    private Builder toBuilder() {
        Builder builder = builder();
        builder.registrations.putAll(registrations);
        return builder;
    }

    private Registration requiredRegistration(String blueId) {
        Registration registration = registrations.get(blueId);
        if (registration == null) {
            throw new BexException(
                    "Unsupported intrinsic BlueId: " + blueId);
        }
        return registration;
    }

    private static String namespaceFor(String blueId) {
        return "intrinsic-" + blueId;
    }

    private static void appendIdentityToken(
            StringBuilder destination,
            String value) {
        destination.append(value.length())
                .append(':')
                .append(value)
                .append(';');
    }

    private static final class Registration {
        private final String registryIdentity;
        private final String namespace;
        private final Map<String, Long> counterWeights;
        private final BexIntrinsicProcessor processor;

        private Registration(String registryIdentity,
                             String namespace,
                             Map<String, Long> counterWeights,
                             BexIntrinsicProcessor processor) {
            this.registryIdentity = registryIdentity;
            this.namespace = namespace;
            this.counterWeights = counterWeights;
            this.processor = processor;
        }
    }

    public static final class Builder {
        private final LinkedHashMap<String, Registration> registrations =
                new LinkedHashMap<>();

        public Builder register(String blueId,
                                String registryIdentity,
                                Map<String, Long> counterWeights,
                                BexIntrinsicProcessor processor) {
            return register(
                    blueId,
                    registryIdentity,
                    namespaceFor(blueId),
                    counterWeights,
                    processor);
        }

        public Builder register(String blueId,
                                String registryIdentity,
                                String namespace,
                                Map<String, Long> counterWeights,
                                BexIntrinsicProcessor processor) {
            if (blueId == null || blueId.trim().isEmpty()) {
                throw new IllegalArgumentException("intrinsic blueId is required");
            }
            if (registryIdentity == null || registryIdentity.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "intrinsic registry identity is required");
            }
            if (namespace == null || namespace.trim().isEmpty()
                    || "bex".equals(namespace)) {
                throw new IllegalArgumentException(
                        "intrinsic gas namespace must be non-empty and disjoint");
            }
            if (namespace.indexOf('/') >= 0) {
                throw new IllegalArgumentException(
                        "intrinsic gas namespace must not contain the reserved '/' separator");
            }
            if (registrations.containsKey(blueId)) {
                throw new IllegalArgumentException(
                        "intrinsic blueId is already registered: " + blueId);
            }
            Objects.requireNonNull(
                    counterWeights, "intrinsic named counter weights");
            if (counterWeights.isEmpty()) {
                throw new IllegalArgumentException(
                        "intrinsic named counter weights must not be empty");
            }
            LinkedHashMap<String, Long> weights = new LinkedHashMap<>();
            for (String counter
                    : BexUnicodeOrder.sortedCopy(counterWeights.keySet())) {
                Long weight = counterWeights.get(counter);
                if (counter == null || counter.trim().isEmpty()
                        || weight == null || weight <= 0L) {
                    throw new IllegalArgumentException(
                            "Intrinsic named gas counters require positive weights");
                }
                weights.put(counter, weight);
            }
            registrations.put(
                    blueId,
                    new Registration(
                            registryIdentity,
                            namespace,
                            Collections.unmodifiableMap(weights),
                            Objects.requireNonNull(processor, "processor")));
            return this;
        }

        public Builder register(Class<?> typeClass,
                                String registryIdentity,
                                Map<String, Long> counterWeights,
                                BexIntrinsicProcessor processor) {
            if (typeClass == null) {
                throw new IllegalArgumentException(
                        "intrinsic type class is required");
            }
            String blueId = BlueIdResolver.resolveBlueId(typeClass);
            if (blueId == null || blueId.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "intrinsic type class must have a resolvable @TypeBlueId: "
                                + typeClass.getName());
            }
            return register(
                    blueId,
                    registryIdentity,
                    counterWeights,
                    processor);
        }

        public BexIntrinsicRegistry build() {
            return registrations.isEmpty()
                    ? EMPTY
                    : new BexIntrinsicRegistry(registrations);
        }
    }
}
