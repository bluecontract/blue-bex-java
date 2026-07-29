package blue.bex.conformance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BexConformanceReportTruthfulnessTest {
    @Test
    void aggregateVariantEvidenceDoesNotAttributeFailureToEveryVariant() {
        assertEquals(
                "passed",
                BexConformanceReportMain.aggregateVariantStatus("passed"));
        assertEquals(
                "indeterminate-after-fixture-failure",
                BexConformanceReportMain.aggregateVariantStatus("failed"));
        assertEquals(
                "not-executed",
                BexConformanceReportMain.aggregateVariantStatus("skipped"));
        assertEquals(
                "not-executed",
                BexConformanceReportMain.aggregateVariantStatus(
                        "not-executed"));
    }

    @Test
    void normativeVectorStatusIsDerivedFromEveryMappedFixture() {
        assertEquals(
                "passed",
                BexConformanceReportMain.aggregateVectorStatus(
                        Arrays.asList("passed", "passed")));
        assertEquals(
                "failed",
                BexConformanceReportMain.aggregateVectorStatus(
                        Arrays.asList("passed", "failed")));
        assertEquals(
                "not-executed",
                BexConformanceReportMain.aggregateVectorStatus(
                        Arrays.asList("passed", "not-executed")));
        assertEquals(
                "invalid-reference",
                BexConformanceReportMain.aggregateVectorStatus(
                        Collections.<String>emptyList()));
    }

    @Test
    void cleanBuildReceiptMustMatchEveryAggregateInput() {
        Map<String, String> receipt =
                new LinkedHashMap<String, String>();
        receipt.put(
                "schema",
                "blue-bex-clean-build-artifacts/1.0");
        receipt.put("status", "passed");
        receipt.put("checkout.clean", "true");
        receipt.put("checkout.root", "/checkout/one");
        receipt.put("checkout.gitDirectory", "/checkout/one/.git");
        receipt.put("checkout.gitStatusSha256", "status");
        receipt.put("checkout.workspaceSha256", "workspace");
        receipt.put("checkout.pathCount", "12");
        receipt.put("commit", "commit");
        receipt.put("project.version", "2.0.0");
        receipt.put("dependency.mode", "standalone-published");
        receipt.put("dependency.coordinate", "group:name:version");
        receipt.put(
                "dependency.effectiveCoordinate",
                "group:name:version");
        receipt.put("dependency.artifact.sha256", "language");
        receipt.put("composite.path", "");
        receipt.put("composite.commit", "");
        receipt.put("composite.dirty", "false");
        receipt.put("composite.gitStatusSha256", "");
        receipt.put("composite.workspaceSha256", "");
        receipt.put("composite.pathCount", "0");
        receipt.put("artifact.main.sha256", "main");

        Map<String, String> aggregate =
                new LinkedHashMap<String, String>(receipt);
        aggregate.put("first.checkout.clean", "true");
        aggregate.put(
                "first.checkout.root",
                receipt.get("checkout.root"));
        aggregate.put(
                "first.checkout.gitDirectory",
                receipt.get("checkout.gitDirectory"));
        aggregate.put(
                "first.checkout.gitStatusSha256",
                receipt.get("checkout.gitStatusSha256"));
        aggregate.put(
                "first.checkout.workspaceSha256",
                receipt.get("checkout.workspaceSha256"));
        aggregate.put(
                "first.checkout.pathCount",
                receipt.get("checkout.pathCount"));
        aggregate.put(
                "artifact.main.byteIdentical",
                "true");

        assertTrue(
                BexConformanceReportMain
                        .cleanBuildReceiptMatchesAggregate(
                                aggregate,
                                "first",
                                receipt,
                                Collections.singleton("main")));

        receipt.put("commit", "different");
        assertFalse(
                BexConformanceReportMain
                        .cleanBuildReceiptMatchesAggregate(
                                aggregate,
                                "first",
                                receipt,
                                Collections.singleton("main")));
    }

    @Test
    void emptyDirectoriesCannotMasqueradeAsCleanGitCheckouts(
            @TempDir Path temporaryDirectory) throws Exception {
        Path checkout =
                Files.createDirectory(
                        temporaryDirectory.resolve("checkout"));
        Path fakeGitDirectory =
                Files.createDirectory(
                        temporaryDirectory.resolve("fake-git"));

        assertFalse(
                BexConformanceReportMain.liveCleanCheckoutMatches(
                        checkout,
                        fakeGitDirectory,
                        "first",
                        Collections.<String, String>emptyMap(),
                        "0000000000000000000000000000000000000000"));
    }
}
