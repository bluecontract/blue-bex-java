package blue.bex.gas;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strict loader for the source-controlled BEX 2.0 portable gas manifest.
 */
final class BexGasManifest {
    static final String RESOURCE =
            "/blue/bex/gas/blue-bex-gas-2.0.yaml";
    static final String RESOURCE_SHA256 =
            "1f689e0cf51b0f9afa6b18a640e0c755470921a7b0d66f62bfc2206679de640d";
    static final String PACKAGE_IDENTITY =
            "sha256:41247c820d91a12fdfc17fd9e787a5d8d668d8acc5954fdcb131715bf9e6147d";

    private final String scheduleId;
    private final String packageIdentity;
    private final Map<String, Long> counterWeights;

    private BexGasManifest(
            String scheduleId,
            String packageIdentity,
            Map<String, Long> counterWeights) {
        this.scheduleId = scheduleId;
        this.packageIdentity = packageIdentity;
        this.counterWeights = Collections.unmodifiableMap(
                new LinkedHashMap<String, Long>(counterWeights));
    }

    static BexGasManifest loadDefault() {
        InputStream resource =
                BexGasManifest.class.getResourceAsStream(RESOURCE);
        if (resource == null) {
            throw new ExceptionInInitializerError(
                    "Missing BEX gas manifest " + RESOURCE);
        }
        try {
            byte[] bytes = readAll(resource);
            String observedHash = sha256(bytes);
            if (!RESOURCE_SHA256.equals(observedHash)) {
                throw new IllegalStateException(
                        "BEX gas manifest resource identity mismatch: "
                                + observedHash);
            }
            return parse(bytes);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        } finally {
            try {
                resource.close();
            } catch (IOException ignored) {
                // The initialization failure, if any, is already authoritative.
            }
        }
    }

    private static BexGasManifest parse(byte[] bytes)
            throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new ByteArrayInputStream(bytes),
                        StandardCharsets.UTF_8));
        String manifestType = null;
        String schedule = null;
        String packageIdentity = null;
        Integer counterCount = null;
        boolean counters = false;
        Map<String, Long> weights =
                new LinkedHashMap<String, Long>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }
            if ("counters:".equals(line)) {
                counters = true;
                continue;
            }
            if (counters && line.startsWith("  ")) {
                String trimmed = line.trim();
                int separator = trimmed.indexOf(':');
                if (separator <= 0) {
                    throw invalid("Malformed counter entry: " + line);
                }
                String name = trimmed.substring(0, separator).trim();
                String rawWeight =
                        trimmed.substring(separator + 1).trim();
                long weight;
                try {
                    weight = Long.parseLong(rawWeight);
                } catch (NumberFormatException exception) {
                    throw invalid(
                            "Invalid gas weight for " + name);
                }
                if (weight <= 0L) {
                    throw invalid(
                            "Portable gas weight must be positive for "
                                    + name);
                }
                if (weights.put(name, weight) != null) {
                    throw invalid(
                            "Duplicate gas counter " + name);
                }
                continue;
            }
            counters = false;
            if (line.startsWith("manifestType:")) {
                manifestType = scalar(line);
            } else if (line.startsWith("schedule:")) {
                schedule = scalar(line);
            } else if (line.startsWith("counterCount:")) {
                try {
                    counterCount =
                            Integer.valueOf(scalar(line));
                } catch (NumberFormatException exception) {
                    throw invalid("Invalid counterCount");
                }
            } else if (line.startsWith("packageIdentity:")) {
                packageIdentity = scalar(line);
            }
        }
        if (!"blue-bex-gas-manifest".equals(manifestType)) {
            throw invalid("Unexpected manifestType " + manifestType);
        }
        if (!PACKAGE_IDENTITY.equals(packageIdentity)) {
            throw invalid(
                    "Unexpected gas manifest package identity "
                            + packageIdentity);
        }
        if (schedule == null || schedule.isEmpty()) {
            throw invalid("Missing gas schedule identifier");
        }
        if (counterCount == null
                || counterCount.intValue() != weights.size()) {
            throw invalid(
                    "counterCount does not match counter catalog");
        }
        return new BexGasManifest(
                schedule,
                packageIdentity,
                weights);
    }

    private static String scalar(String line) {
        int separator = line.indexOf(':');
        String value = line.substring(separator + 1).trim();
        if (value.length() >= 2
                && ((value.startsWith("'")
                && value.endsWith("'"))
                || (value.startsWith("\"")
                && value.endsWith("\"")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException(
                "Invalid BEX gas manifest: " + detail);
    }

    private static byte[] readAll(InputStream input)
            throws IOException {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder value = new StringBuilder(64);
            for (byte item : hash) {
                value.append(Character.forDigit(
                        (item >>> 4) & 0x0f, 16));
                value.append(Character.forDigit(
                        item & 0x0f, 16));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }

    String scheduleId() {
        return scheduleId;
    }

    String packageIdentity() {
        return packageIdentity;
    }

    Map<String, Long> counterWeights() {
        return counterWeights;
    }
}
