package blue.bex.buildlogic.tasks;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates an authorized Commitizen release-version rotation. */
final class CommitizenVersionCheck {
    private static final Pattern VERSION_LINE = Pattern.compile(
            "(?m)^([\\t ]*version[\\t ]*=[\\t ]*\\\")"
                    + "([^\\\"\\r\\n]+)(\\\"[\\t ]*(?:#.*)?)$");
    private static final String VERSION_PLACEHOLDER = "<release-version>";

    private CommitizenVersionCheck() {
    }

    static Result evaluate(
            String current, String baseline, String projectVersion) {
        VersionEntry currentEntry = versionEntry(current);
        VersionEntry baselineEntry = versionEntry(baseline);
        String expectedVersion = projectVersion.endsWith("-SNAPSHOT")
                ? projectVersion.substring(
                        0, projectVersion.length() - "-SNAPSHOT".length())
                : projectVersion;
        boolean matchesProjectVersion = currentEntry.valid
                && currentEntry.version.equals(expectedVersion);
        boolean nonVersionConfigMatchesBaseline = currentEntry.valid
                && baselineEntry.valid
                && currentEntry.normalized.equals(baselineEntry.normalized);
        return new Result(
                currentEntry.version,
                matchesProjectVersion,
                nonVersionConfigMatchesBaseline);
    }

    private static VersionEntry versionEntry(String content) {
        Matcher matcher = VERSION_LINE.matcher(content);
        if (!matcher.find()) {
            return VersionEntry.invalid();
        }
        String version = matcher.group(2);
        String normalized = content.substring(0, matcher.start(2))
                + VERSION_PLACEHOLDER
                + content.substring(matcher.end(2));
        if (matcher.find()) {
            return VersionEntry.invalid();
        }
        return new VersionEntry(true, version, normalized);
    }

    static final class Result {
        final String configuredVersion;
        final boolean matchesProjectVersion;
        final boolean nonVersionConfigMatchesBaseline;
        final boolean passed;

        private Result(
                String configuredVersion,
                boolean matchesProjectVersion,
                boolean nonVersionConfigMatchesBaseline) {
            this.configuredVersion = configuredVersion;
            this.matchesProjectVersion = matchesProjectVersion;
            this.nonVersionConfigMatchesBaseline =
                    nonVersionConfigMatchesBaseline;
            this.passed = matchesProjectVersion
                    && nonVersionConfigMatchesBaseline;
        }
    }

    private static final class VersionEntry {
        private final boolean valid;
        private final String version;
        private final String normalized;

        private VersionEntry(boolean valid, String version, String normalized) {
            this.valid = valid;
            this.version = version;
            this.normalized = normalized;
        }

        private static VersionEntry invalid() {
            return new VersionEntry(false, "unavailable", "");
        }
    }
}
