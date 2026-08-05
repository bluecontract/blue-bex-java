package blue.bex.buildlogic.tasks;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact schema checks for JSON supplied directly to release tasks. */
final class ReleaseEvidenceJson {
    private static final String SHA_256 = "[0-9a-f]{64}";
    private static final String COMMIT = "[0-9a-f]{40}";
    private static final String ARTIFACT_PATH =
            "(?:blue-bex-(?:core|contracts|java)/build/libs/[^/]+\\.jar"
                    + "|build/distributions/[^/]+-source-release\\.zip)";

    private ReleaseEvidenceJson() {
    }

    static Map<String, Object> parseOrEmpty(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return StrictJson.object(text);
        } catch (IllegalArgumentException invalid) {
            return Collections.emptyMap();
        }
    }

    static String stringOrEmpty(
            Map<String, Object> evidence,
            String field) {
        Object value = evidence.get(field);
        return value instanceof String ? (String) value : "";
    }

    static boolean modernizationPassed(Map<String, Object> evidence) {
        return hasString(evidence, "schema",
                "blue-bex-modernization-report/1.0")
                && hasBoolean(evidence, "modernizationReady", true);
    }

    static boolean conformanceReleasePassed(
            Map<String, Object> evidence) {
        return hasString(evidence, "schema",
                "blue-bex-modernization-report/1.0")
                && hasBoolean(evidence, "conformanceReleaseReady", true);
    }

    static boolean publishedLanguagePassed(Map<String, Object> evidence) {
        if (!hasString(evidence, "schema",
                "blue-bex-published-language/2.0")
                || !hasString(evidence, "status", "passed")
                || !hasBoolean(evidence,
                "configuredAssertionsMatch", true)
                || !hasBoolean(evidence, "apiInspectionPassed", true)
                || !hasString(evidence,
                "differentialStatus", "passed")
                || !matches(evidence, "artifactSha256", SHA_256)
                || !matches(evidence, "sourceCommit", COMMIT)
                || !coordinate(stringOrEmpty(evidence, "coordinate"))) {
            return false;
        }
        try {
            List<Object> artifacts = StrictJson.array(
                    evidence, "resolvedArtifacts");
            List<Object> blockers = StrictJson.array(evidence, "blockers");
            if (artifacts.isEmpty() || !blockers.isEmpty()) {
                return false;
            }
            String reviewedHash = StrictJson.string(
                    evidence, "artifactSha256");
            boolean reviewedArtifactPresent = false;
            Set<String> paths = new HashSet<>();
            for (Object value : artifacts) {
                Map<String, Object> artifact = object(value);
                String path = StrictJson.string(artifact, "path");
                String hash = StrictJson.string(artifact, "sha256");
                if (path.trim().isEmpty() || !paths.add(path)
                        || !hash.matches(SHA_256)
                        || StrictJson.integer(artifact, "bytes") <= 0) {
                    return false;
                }
                reviewedArtifactPresent |= reviewedHash.equals(hash);
            }
            return reviewedArtifactPresent;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static boolean differentialPassed(
            Map<String, Object> evidence,
            String expectedCommit) {
        if (!hasString(evidence, "schema",
                "blue-bex-local-published-differential/1.0")
                || !hasString(evidence, "status", "passed")
                || !hasString(evidence,
                "localMode", "local-composite")
                || !hasString(evidence,
                "publishedMode", "standalone-published")
                || !hasBoolean(evidence, "sourceBound", true)
                || !hasBoolean(evidence, "dependenciesDistinct", true)
                || !hasString(evidence,
                "semanticAndGasParity", "passed")
                || !hasString(evidence,
                "exactGasTraceParity", "passed")) {
            return false;
        }
        String commit = stringOrEmpty(evidence, "bexCommit");
        if (!commit.matches(COMMIT)
                || expectedCommit != null
                && !expectedCommit.equals(commit)) {
            return false;
        }
        try {
            return digestPairMatches(StrictJson.object(
                    evidence, "semanticEvidenceSha256"))
                    && digestPairMatches(StrictJson.object(
                    evidence, "gasEvidenceSha256"));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static boolean independentBuildsPassed(
            Map<String, Object> evidence,
            String expectedCommit) {
        if (!hasString(evidence, "schema",
                "blue-bex-independent-clean-builds/2.1")
                || !hasString(evidence, "status", "passed")
                || !hasBoolean(evidence,
                "distinctCheckoutRoots", true)
                || !hasBoolean(evidence,
                "distinctGitDirectories", true)
                || !hasBoolean(evidence,
                "distinctGradleHomes", true)
                || !hasBoolean(evidence,
                "distinctInputManifestFiles", true)
                || !hasInteger(evidence, "checkoutCount", 4)
                || !hasInteger(evidence, "gitDirectoryCount", 4)
                || !hasInteger(evidence, "gradleHomeCount", 4)
                || !hasInteger(evidence, "inputManifestCount", 4)) {
            return false;
        }
        String commit = stringOrEmpty(evidence, "bexCommit");
        if (!commit.matches(COMMIT)
                || expectedCommit != null
                && !expectedCommit.equals(commit)) {
            return false;
        }
        try {
            Set<Path> checkoutRoots = new HashSet<>();
            Set<Path> gitDirectories = new HashSet<>();
            Set<Path> gradleHomes = new HashSet<>();
            Set<Path> manifests = new HashSet<>();
            boolean pairsPassed = buildPairPassed(
                    StrictJson.object(evidence, "standalonePublished"),
                    commit,
                    checkoutRoots,
                    gitDirectories,
                    gradleHomes,
                    manifests)
                    && buildPairPassed(
                    StrictJson.object(evidence, "localComposite"),
                    commit,
                    checkoutRoots,
                    gitDirectories,
                    gradleHomes,
                    manifests);
            return pairsPassed
                    && checkoutRoots.size() == 4
                    && gitDirectories.size() == 4
                    && gradleHomes.size() == 4
                    && manifests.size() == 4;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static boolean buildPairPassed(
            Map<String, Object> pair,
            String expectedCommit,
            Set<Path> checkoutRoots,
            Set<Path> gitDirectories,
            Set<Path> gradleHomes,
            Set<Path> manifests) throws Exception {
        if (!hasString(pair, "status", "passed")
                || !hasBoolean(pair, "exactManifestBytesMatch", true)
                || !hasBoolean(pair, "exactArtifactBytesMatch", true)
                || !hasBoolean(pair, "artifactPathSetMatch", true)
                || !hasBoolean(pair,
                "requiredArtifactRolesPresent", true)) {
            return false;
        }
        long artifactCount = StrictJson.integer(pair, "artifactCount");
        if (artifactCount <= 0) {
            return false;
        }
        LiveBuild first = liveBuild(
                StrictJson.object(pair, "firstBuild"),
                expectedCommit,
                artifactCount);
        LiveBuild second = liveBuild(
                StrictJson.object(pair, "secondBuild"),
                expectedCommit,
                artifactCount);
        checkoutRoots.add(first.checkoutRoot);
        checkoutRoots.add(second.checkoutRoot);
        gitDirectories.add(first.gitDirectory);
        gitDirectories.add(second.gitDirectory);
        gradleHomes.add(first.gradleHome);
        gradleHomes.add(second.gradleHome);
        manifests.add(first.manifestPath);
        manifests.add(second.manifestPath);
        return Arrays.equals(first.manifestBytes, second.manifestBytes);
    }

    private static LiveBuild liveBuild(
            Map<String, Object> build,
            String expectedCommit,
            long expectedArtifactCount) throws Exception {
        if (!hasBoolean(build, "clean", true)
                || !hasString(build, "head", expectedCommit)) {
            throw new IllegalArgumentException(
                    "independent checkout identity is stale");
        }
        Path checkoutRoot = realDirectory(
                StrictJson.string(build, "checkoutRoot"));
        Path gitDirectory = realDirectory(
                StrictJson.string(build, "gitDirectory"));
        Path gradleHome = realDirectory(
                StrictJson.string(build, "gradleHome"));
        String liveHead = gitText(
                checkoutRoot, "rev-parse", "HEAD").trim();
        String liveGitValue = gitText(
                checkoutRoot, "rev-parse", "--git-dir").trim();
        Path liveGitPath = Paths.get(liveGitValue);
        Path liveGitDirectory = realDirectory(
                liveGitPath.isAbsolute()
                        ? liveGitPath.toString()
                        : checkoutRoot.resolve(liveGitPath)
                        .normalize().toString());
        if (!expectedCommit.equals(liveHead)
                || !gitDirectory.equals(liveGitDirectory)
                || gitBytes(checkoutRoot,
                "status",
                "--porcelain",
                "-z",
                "--untracked-files=all").length != 0) {
            throw new IllegalArgumentException(
                    "independent checkout is absent, dirty, or stale");
        }

        Map<String, Object> manifest = StrictJson.object(build, "manifest");
        Path manifestPath = realFile(
                StrictJson.string(manifest, "path"));
        byte[] recordedManifest = Files.readAllBytes(manifestPath);
        List<Object> artifacts = StrictJson.array(build, "artifacts");
        if (artifacts.size() != expectedArtifactCount
                || !hasInteger(manifest,
                "artifactCount", expectedArtifactCount)
                || !hasInteger(manifest,
                "bytes", recordedManifest.length)
                || !sha256(recordedManifest).equals(
                StrictJson.string(manifest, "sha256"))) {
            throw new IllegalArgumentException(
                    "independent manifest evidence is stale");
        }
        Set<String> paths = new HashSet<>();
        boolean core = false;
        boolean contracts = false;
        boolean aggregate = false;
        boolean sourceRelease = false;
        String previousPath = null;
        StringBuilder canonicalManifest = new StringBuilder();
        for (Object value : artifacts) {
            Map<String, Object> artifact = object(value);
            String path = StrictJson.string(artifact, "path");
            String hash = StrictJson.string(artifact, "sha256");
            long bytes = StrictJson.integer(artifact, "bytes");
            if (!safeArtifactPath(path) || !path.matches(ARTIFACT_PATH)
                    || !paths.add(path) || !hash.matches(SHA_256)
                    || bytes <= 0
                    || (previousPath != null
                    && previousPath.compareTo(path) >= 0)) {
                throw new IllegalArgumentException(
                        "invalid independent artifact evidence");
            }
            Path artifactPath = checkoutRoot.resolve(path).normalize();
            if (!artifactPath.startsWith(checkoutRoot)
                    || !Files.isRegularFile(artifactPath)) {
                throw new IllegalArgumentException(
                        "independent artifact is absent");
            }
            Path realArtifact = artifactPath.toRealPath();
            if (!realArtifact.startsWith(checkoutRoot)
                    || Files.size(realArtifact) != bytes
                    || !hash.equals(sha256(
                    Files.readAllBytes(realArtifact)))) {
                throw new IllegalArgumentException(
                        "independent artifact bytes are stale");
            }
            canonicalManifest.append(hash).append("  ")
                    .append(path).append('\n');
            previousPath = path;
            core |= path.startsWith("blue-bex-core/build/libs/")
                    && path.endsWith(".jar");
            contracts |= path.startsWith(
                    "blue-bex-contracts/build/libs/")
                    && path.endsWith(".jar");
            aggregate |= path.startsWith(
                    "blue-bex-java/build/libs/")
                    && path.endsWith(".jar");
            sourceRelease |= path.startsWith("build/distributions/")
                    && path.endsWith("-source-release.zip");
        }
        byte[] manifestBytes = canonicalManifest.toString()
                .getBytes(StandardCharsets.UTF_8);
        String manifestHash = sha256(manifestBytes);
        if (!core || !contracts || !aggregate || !sourceRelease
                || !Arrays.equals(recordedManifest, manifestBytes)
                || !manifestHash.equals(
                StrictJson.string(manifest, "artifactSetSha256"))) {
            throw new IllegalArgumentException(
                    "independent manifest does not match live artifacts");
        }
        return new LiveBuild(
                checkoutRoot,
                gitDirectory,
                gradleHome,
                manifestPath,
                manifestBytes);
    }

    private static boolean digestPairMatches(Map<String, Object> pair) {
        String local = stringOrEmpty(pair, "local");
        String published = stringOrEmpty(pair, "published");
        return local.matches(SHA_256) && local.equals(published);
    }

    private static boolean safeArtifactPath(String path) {
        return !path.isEmpty() && !path.startsWith("/")
                && path.indexOf('\\') < 0
                && !path.equals("..")
                && !path.startsWith("../")
                && !path.contains("/../")
                && !path.endsWith("/..");
    }

    private static Path realDirectory(String value) throws IOException {
        Path claimed = Paths.get(value);
        if (!claimed.isAbsolute()) {
            throw new IllegalArgumentException(
                    "evidence directory must be absolute");
        }
        Path normalized = claimed.normalize();
        Path real = normalized.toRealPath();
        if (!normalized.equals(real) || !Files.isDirectory(real)) {
            throw new IllegalArgumentException(
                    "evidence directory is absent or not canonical");
        }
        return real;
    }

    private static Path realFile(String value) throws IOException {
        Path claimed = Paths.get(value);
        if (!claimed.isAbsolute()) {
            throw new IllegalArgumentException(
                    "evidence file must be absolute");
        }
        Path normalized = claimed.normalize();
        Path real = normalized.toRealPath();
        if (!normalized.equals(real) || !Files.isRegularFile(real)) {
            throw new IllegalArgumentException(
                    "evidence file is absent or not canonical");
        }
        return real;
    }

    private static String gitText(
            Path directory,
            String... arguments) throws IOException, InterruptedException {
        return new String(
                gitBytes(directory, arguments),
                StandardCharsets.UTF_8);
    }

    private static byte[] gitBytes(
            Path directory,
            String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = process.getInputStream().read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        if (process.waitFor() != 0) {
            throw new IOException(
                    output.toString(StandardCharsets.UTF_8.name()));
        }
        return output.toByteArray();
    }

    private static boolean coordinate(String value) {
        return value.matches("[^:]+:[^:]+:[^:]+");
    }

    private static boolean hasString(
            Map<String, Object> evidence,
            String field,
            String expected) {
        return expected.equals(evidence.get(field));
    }

    private static boolean hasBoolean(
            Map<String, Object> evidence,
            String field,
            boolean expected) {
        return Boolean.valueOf(expected).equals(evidence.get(field));
    }

    private static boolean hasInteger(
            Map<String, Object> evidence,
            String field,
            long expected) {
        try {
            return StrictJson.integer(evidence, field) == expected;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean matches(
            Map<String, Object> evidence,
            String field,
            String pattern) {
        return stringOrEmpty(evidence, field).matches(pattern);
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("array entry must be an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    private static String sha256(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest.digest(value)) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static final class LiveBuild {
        private final Path checkoutRoot;
        private final Path gitDirectory;
        private final Path gradleHome;
        private final Path manifestPath;
        private final byte[] manifestBytes;

        private LiveBuild(
                Path checkoutRoot,
                Path gitDirectory,
                Path gradleHome,
                Path manifestPath,
                byte[] manifestBytes) {
            this.checkoutRoot = checkoutRoot;
            this.gitDirectory = gitDirectory;
            this.gradleHome = gradleHome;
            this.manifestPath = manifestPath;
            this.manifestBytes = manifestBytes;
        }
    }
}
