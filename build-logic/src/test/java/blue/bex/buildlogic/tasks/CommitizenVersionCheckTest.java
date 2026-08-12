package blue.bex.buildlogic.tasks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CommitizenVersionCheckTest {
    private static final String BASELINE = String.join("\n",
            "[tool.commitizen]",
            "name = \"cz_conventional_commits\"",
            "tag_format = \"v$version\"",
            "version_scheme = \"semver\"",
            "version = \"1.1.0-rc.2\"",
            "update_changelog_on_bump = true",
            "");

    @Test
    void acceptsReleaseVersionRotation() {
        CommitizenVersionCheck.Result result = CommitizenVersionCheck.evaluate(
                BASELINE.replace("1.1.0-rc.2", "1.1.0-rc.3"),
                BASELINE,
                "1.1.0-rc.3");

        assertTrue(result.matchesProjectVersion);
        assertTrue(result.nonVersionConfigMatchesBaseline);
        assertTrue(result.passed);
    }

    @Test
    void acceptsLocalSnapshotForConfiguredReleaseVersion() {
        CommitizenVersionCheck.Result result = CommitizenVersionCheck.evaluate(
                BASELINE.replace("1.1.0-rc.2", "1.1.0-rc.3"),
                BASELINE,
                "1.1.0-rc.3-SNAPSHOT");

        assertTrue(result.passed);
    }

    @Test
    void rejectsProjectVersionMismatch() {
        CommitizenVersionCheck.Result result = CommitizenVersionCheck.evaluate(
                BASELINE.replace("1.1.0-rc.2", "1.1.0-rc.3"),
                BASELINE,
                "1.1.0-rc.4");

        assertFalse(result.matchesProjectVersion);
        assertFalse(result.passed);
    }

    @Test
    void rejectsNonVersionConfigurationChange() {
        CommitizenVersionCheck.Result result = CommitizenVersionCheck.evaluate(
                BASELINE
                        .replace("1.1.0-rc.2", "1.1.0-rc.3")
                        .replace("tag_format = \"v$version\"",
                                "tag_format = \"release-$version\""),
                BASELINE,
                "1.1.0-rc.3");

        assertFalse(result.nonVersionConfigMatchesBaseline);
        assertFalse(result.passed);
    }

    @Test
    void rejectsDuplicateVersionDeclaration() {
        CommitizenVersionCheck.Result result = CommitizenVersionCheck.evaluate(
                BASELINE + "version = \"1.1.0-rc.3\"\n",
                BASELINE,
                "1.1.0-rc.3");

        assertFalse(result.matchesProjectVersion);
        assertFalse(result.passed);
    }
}
