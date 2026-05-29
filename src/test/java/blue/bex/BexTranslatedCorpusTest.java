package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexExecutionContext;
import blue.bex.api.BexProgramSource;
import blue.bex.api.FrozenBexDocumentView;
import blue.bex.result.BexExecutionResult;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BexTranslatedCorpusTest {
    private final BexEngine engine = BexEngine.builder().build();

    @Test
    void corpusHasPlannedShape() {
        assertEquals(30, kyvernoValidateCases().size());
        assertEquals(20, kyvernoMutateGenerateCases().size());
        assertEquals(20, queryTransformCases().size());
        assertEquals(10, jsonPatchCases().size());
    }

    @TestFactory
    Collection<DynamicTest> translatedCorpus() {
        List<CorpusCase> cases = new ArrayList<>();
        cases.addAll(kyvernoValidateCases());
        cases.addAll(kyvernoMutateGenerateCases());
        cases.addAll(queryTransformCases());
        cases.addAll(jsonPatchCases());

        List<DynamicTest> tests = new ArrayList<>();
        for (CorpusCase testCase : cases) {
            tests.add(DynamicTest.dynamicTest(testCase.category + " / " + testCase.source, () -> runCase(testCase)));
        }
        return tests;
    }

    private void runCase(CorpusCase testCase) {
        BexExecutionResult result = engine.compileAndExecute(
                BexProgramSource.inline(frozen(testCase.program)),
                context(testCase.document, testCase.documentScope));
        if (testCase.expectedValue != null) {
            assertEquals(testCase.expectedValue, simple(result.value()));
        }
        if (testCase.expectedChangeset != null) {
            assertEquals(testCase.expectedChangeset, simple(result.changeset().asValue()));
        }
    }

    private List<CorpusCase> kyvernoValidateCases() {
        List<CorpusCase> cases = new ArrayList<>();
        cases.add(validate("require-labels/app",
                obj("kind", "Pod", "metadata", obj("labels", obj())),
                not(hasKey(doc("/metadata/labels"), "app"))));
        cases.add(validate("require-labels/owner",
                obj("kind", "Deployment", "metadata", obj("labels", obj("app", "demo"))),
                not(hasKey(doc("/metadata/labels"), "owner"))));
        cases.add(validate("require-annotations/contact",
                obj("kind", "Service", "metadata", obj("annotations", obj())),
                not(hasKey(doc("/metadata/annotations"), "contact"))));
        cases.add(validate("require-namespace-labels/team",
                obj("kind", "Namespace", "metadata", obj("labels", obj("env", "prod"))),
                not(hasKey(doc("/metadata/labels"), "team"))));
        cases.add(validate("disallow-privileged-containers",
                pod(container("app", "corp/app:1.0", "securityContext", obj("privileged", true))),
                someContainer(eq(var("c", "/securityContext/privileged"), true))));
        cases.add(validate("disallow-host-network",
                obj("kind", "Pod", "spec", obj("hostNetwork", true, "containers", list(container("app", "corp/app:1.0")))),
                eq(doc("/spec/hostNetwork"), true)));
        cases.add(validate("disallow-host-pid",
                obj("kind", "Pod", "spec", obj("hostPID", true, "containers", list(container("app", "corp/app:1.0")))),
                eq(doc("/spec/hostPID"), true)));
        cases.add(validate("disallow-host-ipc",
                obj("kind", "Pod", "spec", obj("hostIPC", true, "containers", list(container("app", "corp/app:1.0")))),
                eq(doc("/spec/hostIPC"), true)));
        cases.add(validate("disallow-host-path",
                obj("kind", "Pod", "spec", obj("volumes", list(obj("name", "host", "hostPath", obj("path", "/var/run"))), "containers", list(container("app", "corp/app:1.0")))),
                some(listOrEmpty(doc("/spec/volumes")), "v", exists(var("v", "/hostPath")))));
        cases.add(validate("require-run-as-non-root",
                pod(container("app", "corp/app:1.0", "securityContext", obj("runAsNonRoot", false))),
                someContainer(ne(var("c", "/securityContext/runAsNonRoot"), true))));
        cases.add(validate("require-requests-cpu",
                pod(container("app", "corp/app:1.0", "resources", obj("requests", obj("memory", "128Mi")))),
                containerMissing("/resources/requests/cpu")));
        cases.add(validate("require-requests-memory",
                pod(container("app", "corp/app:1.0", "resources", obj("requests", obj("cpu", "100m")))),
                containerMissing("/resources/requests/memory")));
        cases.add(validate("require-limits-cpu",
                pod(container("app", "corp/app:1.0", "resources", obj("limits", obj("memory", "256Mi")))),
                containerMissing("/resources/limits/cpu")));
        cases.add(validate("require-limits-memory",
                pod(container("app", "corp/app:1.0", "resources", obj("limits", obj("cpu", "500m")))),
                containerMissing("/resources/limits/memory")));
        cases.add(validate("disallow-latest-tag",
                pod(container("app", "nginx:latest")),
                someContainer(includes(list("nginx:latest", "busybox:latest", "redis:latest"), var("c", "/image")))));
        cases.add(validate("restrict-image-registries",
                pod(container("app", "docker.io/library/nginx:1.25")),
                someContainer(not(startsWith(var("c", "/image"), "registry.corp/")))));
        cases.add(validate("require-read-only-root-filesystem",
                pod(container("app", "corp/app:1.0", "securityContext", obj("readOnlyRootFilesystem", false))),
                someContainer(ne(var("c", "/securityContext/readOnlyRootFilesystem"), true))));
        cases.add(validate("disallow-privilege-escalation",
                pod(container("app", "corp/app:1.0", "securityContext", obj("allowPrivilegeEscalation", true))),
                someContainer(eq(var("c", "/securityContext/allowPrivilegeEscalation"), true))));
        cases.add(validate("disallow-host-ports",
                pod(container("app", "corp/app:1.0", "ports", list(obj("containerPort", 8080, "hostPort", 80)))),
                someContainer(some(listOrEmpty(var("c", "/ports")), "p", exists(var("p", "/hostPort"))))));
        cases.add(validate("require-liveness-probe",
                pod(container("app", "corp/app:1.0", "readinessProbe", obj("httpGet", obj("path", "/ready")))),
                containerMissing("/livenessProbe")));
        cases.add(validate("require-readiness-probe",
                pod(container("app", "corp/app:1.0", "livenessProbe", obj("httpGet", obj("path", "/live")))),
                containerMissing("/readinessProbe")));
        cases.add(validate("require-deployment-replicas",
                obj("kind", "Deployment", "spec", obj("replicas", 1)),
                lt(doc("/spec/replicas"), 2)));
        cases.add(validate("disallow-service-loadbalancer",
                obj("kind", "Service", "spec", obj("type", "LoadBalancer")),
                eq(doc("/spec/type"), "LoadBalancer")));
        cases.add(validate("require-ingress-tls",
                obj("kind", "Ingress", "spec", obj("rules", list(obj("host", "example.com")))),
                not(truthy(doc("/spec/tls")))));
        cases.add(validate("pdb-minavailable",
                obj("kind", "PodDisruptionBudget", "spec", obj("selector", obj("matchLabels", obj("app", "demo")))),
                not(exists(doc("/spec/minAvailable")))));
        cases.add(validate("disallow-default-namespace",
                obj("kind", "Pod", "metadata", obj("namespace", "default"), "spec", obj("containers", list(container("app", "corp/app:1.0")))),
                eq(doc("/metadata/namespace"), "default")));
        cases.add(validate("restrict-seccomp",
                podWithSpec(obj("securityContext", obj("seccompProfile", obj("type", "Unconfined")), "containers", list(container("app", "corp/app:1.0")))),
                ne(doc("/spec/securityContext/seccompProfile/type"), "RuntimeDefault")));
        cases.add(validate("require-drop-all-capabilities",
                pod(container("app", "corp/app:1.0", "securityContext", obj("capabilities", obj("drop", list("NET_RAW"))))),
                someContainer(not(includes(listOrEmpty(var("c", "/securityContext/capabilities/drop")), "ALL")))));
        cases.add(validate("require-non-root-group",
                pod(container("app", "corp/app:1.0", "securityContext", obj("runAsGroup", 0))),
                someContainer(or(not(exists(var("c", "/securityContext/runAsGroup"))), lte(var("c", "/securityContext/runAsGroup"), 0)))));
        cases.add(validate("require-image-tag",
                pod(container("app", "registry.corp/app")),
                someContainer(lte(size(split(var("c", "/image"), ":")), 1))));
        return cases;
    }

    private List<CorpusCase> kyvernoMutateGenerateCases() {
        List<CorpusCase> cases = new ArrayList<>();
        cases.add(changes("add-safe-to-evict",
                pod(container("app", "corp/app:1.0")),
                list(patch("add", "/metadata/annotations/cluster-autoscaler.kubernetes.io~1safe-to-evict", "true")),
                l(p("add", "/metadata/annotations/cluster-autoscaler.kubernetes.io~1safe-to-evict", "true"))));
        cases.add(changes("add-team-label",
                obj("kind", "Namespace", "metadata", obj("name", "payments", "labels", obj())),
                list(patch("add", "/metadata/labels/team", "platform")),
                l(p("add", "/metadata/labels/team", "platform"))));
        cases.add(changes("add-default-requests-cpu",
                pod(container("app", "corp/app:1.0")),
                defaultContainerPatch("/resources/requests/cpu", "100m"),
                l(p("add", "/spec/containers/0/resources/requests/cpu", "100m"))));
        cases.add(changes("add-default-requests-memory",
                pod(container("app", "corp/app:1.0")),
                defaultContainerPatch("/resources/requests/memory", "128Mi"),
                l(p("add", "/spec/containers/0/resources/requests/memory", "128Mi"))));
        cases.add(changes("add-default-limits-cpu",
                pod(container("app", "corp/app:1.0")),
                defaultContainerPatch("/resources/limits/cpu", "500m"),
                l(p("add", "/spec/containers/0/resources/limits/cpu", "500m"))));
        cases.add(changes("add-default-limits-memory",
                pod(container("app", "corp/app:1.0")),
                defaultContainerPatch("/resources/limits/memory", "256Mi"),
                l(p("add", "/spec/containers/0/resources/limits/memory", "256Mi"))));
        cases.add(changes("add-image-pull-policy",
                pod(container("app", "corp/app:1.0")),
                defaultContainerPatch("/imagePullPolicy", "IfNotPresent"),
                l(p("add", "/spec/containers/0/imagePullPolicy", "IfNotPresent"))));
        cases.add(changes("add-run-as-non-root",
                pod(container("app", "corp/app:1.0")),
                list(patch("add", "/spec/securityContext/runAsNonRoot", true)),
                l(p("add", "/spec/securityContext/runAsNonRoot", true))));
        cases.add(changes("add-seccomp",
                pod(container("app", "corp/app:1.0")),
                list(patch("add", "/spec/securityContext/seccompProfile/type", "RuntimeDefault")),
                l(p("add", "/spec/securityContext/seccompProfile/type", "RuntimeDefault"))));
        cases.add(changes("add-read-only-rootfs",
                pod(container("app", "corp/app:1.0"), container("sidecar", "corp/sidecar:1.0")),
                mapIndexed(containers(), "c", "i", patch("add", pointerJoin("spec", "containers", var("i"), "securityContext", "readOnlyRootFilesystem"), true)),
                l(p("add", "/spec/containers/0/securityContext/readOnlyRootFilesystem", true),
                        p("add", "/spec/containers/1/securityContext/readOnlyRootFilesystem", true))));
        cases.add(changes("add-allow-privilege-escalation-false",
                pod(container("app", "corp/app:1.0")),
                mapIndexed(containers(), "c", "i", patch("add", pointerJoin("spec", "containers", var("i"), "securityContext", "allowPrivilegeEscalation"), false)),
                l(p("add", "/spec/containers/0/securityContext/allowPrivilegeEscalation", false))));
        cases.add(changes("add-env-vars-from-configmap",
                pod(container("app", "corp/app:1.0")),
                mapIndexed(containers(), "c", "i", patch("add", pointerJoin("spec", "containers", var("i"), "env", 0), obj("name", "CLUSTER_NAME", "valueFrom", obj("configMapKeyRef", obj("name", "cluster-info", "key", "name"))))),
                l(p("add", "/spec/containers/0/env/0", m("name", "CLUSTER_NAME", "valueFrom", m("configMapKeyRef", m("key", "name", "name", "cluster-info")))))));
        cases.add(changes("add-node-selector",
                pod(container("app", "corp/app:1.0")),
                list(patch("add", "/spec/nodeSelector/kubernetes.io~1os", "linux")),
                l(p("add", "/spec/nodeSelector/kubernetes.io~1os", "linux"))));
        cases.add(changes("add-toleration",
                pod(container("app", "corp/app:1.0")),
                list(patch("add", "/spec/tolerations/0", obj("key", "dedicated", "operator", "Equal", "value", "apps", "effect", "NoSchedule"))),
                l(p("add", "/spec/tolerations/0", m("effect", "NoSchedule", "key", "dedicated", "operator", "Equal", "value", "apps")))));
        cases.add(changes("add-priority-class",
                pod(container("app", "corp/app:1.0")),
                list(patch("add", "/spec/priorityClassName", "standard")),
                l(p("add", "/spec/priorityClassName", "standard"))));
        cases.add(changes("backup-all-volumes",
                obj("kind", "Pod", "metadata", obj("annotations", obj()), "spec", obj("volumes", list(
                        obj("name", "data", "persistentVolumeClaim", obj("claimName", "data-pvc")),
                        obj("name", "cache", "emptyDir", obj())))),
                list(patch("add", "/metadata/annotations/backup.velero.io~1backup-volumes",
                        join(map(filter(listOrEmpty(doc("/spec/volumes")), "v", exists(var("v", "/persistentVolumeClaim"))), "v", var("v", "/name")), ","))),
                l(p("add", "/metadata/annotations/backup.velero.io~1backup-volumes", "data"))));
        cases.add(changes("generate-default-networkpolicy",
                obj("kind", "Namespace", "metadata", obj("name", "payments")),
                list(patch("add", pointerJoin("generated", "NetworkPolicy", doc("/metadata/name"), "default-deny"),
                        obj("kind", "NetworkPolicy", "metadata", obj("name", "default-deny", "namespace", doc("/metadata/name")), "spec", obj("podSelector", op("$emptyObject", true), "policyTypes", list("Ingress", "Egress"))))),
                l(p("add", "/generated/NetworkPolicy/payments/default-deny", m("kind", "NetworkPolicy", "metadata", m("name", "default-deny", "namespace", "payments"), "spec", m("podSelector", m(), "policyTypes", l("Ingress", "Egress")))))));
        cases.add(changes("generate-resourcequota",
                obj("kind", "Namespace", "metadata", obj("name", "team-a")),
                list(patch("add", pointerJoin("generated", "ResourceQuota", doc("/metadata/name"), "default-quota"),
                        obj("kind", "ResourceQuota", "metadata", obj("name", "default-quota", "namespace", doc("/metadata/name")), "spec", obj("hard", obj("requests.cpu", "4", "requests.memory", "8Gi"))))),
                l(p("add", "/generated/ResourceQuota/team-a/default-quota", m("kind", "ResourceQuota", "metadata", m("name", "default-quota", "namespace", "team-a"), "spec", m("hard", m("requests.cpu", "4", "requests.memory", "8Gi")))))));
        cases.add(changes("generate-limitrange",
                obj("kind", "Namespace", "metadata", obj("name", "team-b")),
                list(patch("add", pointerJoin("generated", "LimitRange", doc("/metadata/name"), "defaults"),
                        obj("kind", "LimitRange", "metadata", obj("name", "defaults", "namespace", doc("/metadata/name")), "spec", obj("limits", list(obj("type", "Container", "defaultRequest", obj("cpu", "100m", "memory", "128Mi"))))))),
                l(p("add", "/generated/LimitRange/team-b/defaults", m("kind", "LimitRange", "metadata", m("name", "defaults", "namespace", "team-b"), "spec", m("limits", l(m("defaultRequest", m("cpu", "100m", "memory", "128Mi"), "type", "Container"))))))));
        cases.add(changes("generate-poddisruptionbudget",
                obj("kind", "Deployment", "metadata", obj("name", "api", "namespace", "prod", "labels", obj("app", "api"))),
                list(patch("add", pointerJoin("generated", "PodDisruptionBudget", doc("/metadata/namespace"), doc("/metadata/name")),
                        obj("kind", "PodDisruptionBudget", "metadata", obj("name", doc("/metadata/name"), "namespace", doc("/metadata/namespace")), "spec", obj("minAvailable", 1, "selector", obj("matchLabels", doc("/metadata/labels")))))),
                l(p("add", "/generated/PodDisruptionBudget/prod/api", m("kind", "PodDisruptionBudget", "metadata", m("name", "api", "namespace", "prod"), "spec", m("minAvailable", bi(1), "selector", m("matchLabels", m("app", "api"))))))));
        return cases;
    }

    private List<CorpusCase> queryTransformCases() {
        List<CorpusCase> cases = new ArrayList<>();
        cases.add(query("JMESPath locations[?state=='WA'].name",
                obj("locations", list(obj("name", "Seattle", "state", "WA"), obj("name", "Portland", "state", "OR"))),
                map(filter(doc("/locations"), "x", eq(var("x", "/state"), "WA")), "x", var("x", "/name")),
                l("Seattle")));
        cases.add(query("JMESPath people[?age > `30`].name",
                obj("people", list(obj("name", "Ada", "age", 36), obj("name", "Lin", "age", 29))),
                map(filter(doc("/people"), "p", gt(var("p", "/age"), 30)), "p", var("p", "/name")),
                l("Ada")));
        cases.add(query("JMESPath reservations[].instances[].state",
                obj("reservations", list(obj("instances", list(obj("state", "running"), obj("state", "stopped"))), obj("instances", list(obj("state", "pending"))))),
                flatMap(doc("/reservations"), "r", map(var("r", "/instances"), "i", var("i", "/state"))),
                l("running", "stopped", "pending")));
        cases.add(query("JMESPath machines[*].name",
                obj("machines", list(obj("name", "a"), obj("name", "b"))),
                map(doc("/machines"), "m", var("m", "/name")),
                l("a", "b")));
        cases.add(query("JMESPath contains(tags, 'admin')",
                obj("people", list(obj("name", "Ada", "tags", list("admin", "db")), obj("name", "Lin", "tags", list("api")))),
                map(filter(doc("/people"), "p", includes(listOrEmpty(var("p", "/tags")), "admin")), "p", var("p", "/name")),
                l("Ada")));
        cases.add(query("JMESPath keys(object)",
                obj("object", obj("b", 2, "a", 1)),
                op("$keys", doc("/object")),
                l("a", "b")));
        cases.add(query("JMESPath length(items)",
                obj("items", list("a", "b", "c")),
                size(doc("/items")),
                bi(3)));
        cases.add(query("JSONata $sum(items.price)",
                obj("items", list(obj("price", 10), obj("price", 15), obj("price", 5))),
                reduce(doc("/items"), "total", 0, "item", add(var("total"), var("item", "/price"))),
                bi(30)));
        cases.add(query("JSONata $max(items.price)",
                obj("items", list(obj("price", 10), obj("price", 27), obj("price", 5))),
                reduce(doc("/items"), "max", 0, "item", choose(gt(var("item", "/price"), var("max")), var("item", "/price"), var("max"))),
                bi(27)));
        cases.add(query("JSONata Account.Order.Product.{name,price}",
                obj("products", list(obj("name", "Hat", "price", 12, "sku", "h1"), obj("name", "Bag", "price", 30, "sku", "b1"))),
                map(doc("/products"), "p", obj("name", var("p", "/name"), "price", var("p", "/price"))),
                l(m("name", "Hat", "price", bi(12)), m("name", "Bag", "price", bi(30)))));
        cases.add(query("JSONata object pick via entries/filter",
                obj("resource", obj("metadata", obj("name", "api"), "spec", obj("replicas", 3), "status", obj("ready", 2))),
                op("$objectFromEntries", filter(op("$entries", doc("/resource")), "entry", includes(list("metadata", "spec"), var("entry", "/key")))),
                m("metadata", m("name", "api"), "spec", m("replicas", bi(3)))));
        cases.add(query("JSONata object omit via entries/filter",
                obj("resource", obj("metadata", obj("name", "api"), "spec", obj("replicas", 3), "status", obj("ready", 2))),
                op("$objectFromEntries", filter(op("$entries", doc("/resource")), "entry", not(includes(list("status"), var("entry", "/key"))))),
                m("metadata", m("name", "api"), "spec", m("replicas", bi(3)))));
        cases.add(query("JSONata count by severity",
                obj("findings", list(obj("severity", "high"), obj("severity", "low"), obj("severity", "high"))),
                reduce(doc("/findings"), "counts", obj(), "f",
                        op("$objectSet", obj("object", var("counts"), "key", var("f", "/severity"),
                                "val", add(choose(exists(get(var("counts"), var("f", "/severity"))), get(var("counts"), var("f", "/severity")), 0), 1)))),
                m("high", bi(2), "low", bi(1))));
        cases.add(query("JMESPath first matching container",
                pod(container("web", "corp/web:1.0"), container("api", "corp/api:1.0")),
                op("$find", obj("in", containers(), "item", "c", "where", eq(var("c", "/name"), "api"))),
                m("image", "corp/api:1.0", "name", "api")));
        cases.add(query("JMESPath any container missing resources",
                pod(container("web", "corp/web:1.0", "resources", obj("requests", obj("cpu", "100m"))), container("api", "corp/api:1.0")),
                some(containers(), "c", not(exists(var("c", "/resources/requests/cpu")))),
                true));
        cases.add(query("JSONata flatten errors arrays",
                obj("checks", list(obj("errors", list("a", "b")), obj("errors", list()), obj("errors", list("c")))),
                flatMap(doc("/checks"), "check", listOrEmpty(var("check", "/errors"))),
                l("a", "b", "c")));
        cases.add(query("JMESPath find entry in object",
                obj("limits", obj("memory", "512Mi", "cpu", "500m")),
                op("$findEntry", obj("in", doc("/limits"), "item", "v", "key", "k", "index", "i", "where", eq(var("k"), "memory"))),
                m("index", bi(1), "key", "memory", "val", "512Mi")));
        cases.add(query("JSONata build object from list",
                obj("labels", list(obj("key", "app", "value", "api"), obj("key", "team", "value", "platform"))),
                op("$objectFromEntries", map(doc("/labels"), "label", obj("key", var("label", "/key"), "val", var("label", "/value")))),
                m("app", "api", "team", "platform")));
        cases.add(query("JMESPath starts_with(image, registry)",
                pod(container("app", "registry.corp/app:1.0")),
                some(containers(), "c", startsWith(var("c", "/image"), "registry.corp/")),
                true));
        cases.add(query("JSONata split image tag",
                pod(container("app", "registry.corp/app:1.0")),
                op("$listGet", obj("list", split(pointerGet(op("$find", obj("in", containers(), "item", "c", "where", eq(var("c", "/name"), "app"))), "/image"), ":"), "index", 1)),
                "1.0"));
        return cases;
    }

    private List<CorpusCase> jsonPatchCases() {
        List<CorpusCase> cases = new ArrayList<>();
        cases.add(patchCase("JSON Patch escaped slash path",
                obj(),
                list(patch("add", pointerJoin("metadata", "labels", "app.kubernetes.io/name"), "api")),
                l(p("add", "/metadata/labels/app.kubernetes.io~1name", "api"))));
        cases.add(patchCase("JSON Patch escaped tilde path",
                obj(),
                list(patch("replace", pointerJoin("metadata", "labels", "team~owner"), "platform")),
                l(p("replace", "/metadata/labels/team~0owner", "platform"))));
        cases.add(patchCase("JSON Patch duplicate path order",
                obj(),
                list(patch("replace", "/status", "first"), patch("replace", "/status", "second")),
                l(p("replace", "/status", "first"), p("replace", "/status", "second"))));
        cases.add(CorpusCase.changes("json-patch", "JSON Patch remove ignores val expression",
                stepDo(list(
                        op("$appendChange", obj("op", "remove", "path", "/status", "val", op("$integer", "not-an-integer"))),
                        op("$return", obj("changeset", op("$changeset", true), "events", list())))),
                obj("status", "old"), "/", l(m("op", "remove", "path", "/status"))));
        cases.add(patchCase("JSON Patch map list indexes",
                pod(container("web", "corp/web:1.0"), container("api", "corp/api:1.0")),
                mapIndexed(containers(), "c", "i", patch("replace", pointerJoin("spec", "containers", var("i"), "imagePullPolicy"), "Always")),
                l(p("replace", "/spec/containers/0/imagePullPolicy", "Always"), p("replace", "/spec/containers/1/imagePullPolicy", "Always"))));
        cases.add(patchCase("JSON Patch flatMap zero-or-one patches",
                pod(container("web", "corp/web:1.0", "imagePullPolicy", "Always"), container("api", "corp/api:1.0")),
                flatMapIndexed(containers(), "c", "i",
                        choose(exists(var("c", "/imagePullPolicy")), list(), list(patch("add", pointerJoin("spec", "containers", var("i"), "imagePullPolicy"), "IfNotPresent")))),
                l(p("add", "/spec/containers/1/imagePullPolicy", "IfNotPresent"))));
        cases.add(patchCase("JSON Patch objectFromEntries patch value",
                obj("labels", list(obj("key", "app", "value", "api"), obj("key", "team", "value", "platform"))),
                list(patch("add", "/metadata/labels", op("$objectFromEntries", map(doc("/labels"), "label", obj("key", var("label", "/key"), "val", var("label", "/value")))))),
                l(p("add", "/metadata/labels", m("app", "api", "team", "platform")))));
        cases.add(patchCase("JSON Patch root replace",
                obj("old", true),
                list(patch("replace", "/", obj("status", "ready"))),
                l(p("replace", "/", m("status", "ready")))));
        cases.add(patchCase("JSON Patch relative scoped path",
                obj("spec", obj("replicas", 1)),
                "/spec",
                list(patch("replace", "replicas", 3)),
                l(p("replace", "/spec/replicas", bi(3)))));
        cases.add(patchCase("JSON Patch remove then add same path",
                obj("status", "old"),
                list(patch("remove", "/status"), patch("add", "/status", "new")),
                l(m("op", "remove", "path", "/status"), p("add", "/status", "new"))));
        return cases;
    }

    private CorpusCase validate(String source, Node document, Node violation) {
        Node invalid = obj("changeset", list(), "events", list(obj("type", "Policy/Violation", "policy", source)));
        Node program = stepDo(list(
                op("$returnIf", obj("cond", violation, "expr", invalid)),
                op("$return", obj("changeset", list(), "events", list()))
        ));
        return CorpusCase.value("kyverno-validate", source, program, document,
                m("changeset", l(), "events", l(m("policy", source, "type", "Policy/Violation"))));
    }

    private CorpusCase changes(String source, Node document, Node changes, Object expectedChangeset) {
        return changes(source, document, "/", changes, expectedChangeset);
    }

    private CorpusCase changes(String source, Node document, String scope, Node changes, Object expectedChangeset) {
        return CorpusCase.changes("kyverno-mutate-generate", source, changesetProgram(changes), document, scope, expectedChangeset);
    }

    private CorpusCase patchCase(String source, Node document, Node changes, Object expectedChangeset) {
        return patchCase(source, document, "/", changes, expectedChangeset);
    }

    private CorpusCase patchCase(String source, Node document, String scope, Node changes, Object expectedChangeset) {
        return CorpusCase.changes("json-patch", source, changesetProgram(changes), document, scope, expectedChangeset);
    }

    private CorpusCase query(String source, Node document, Node expr, Object expectedValue) {
        return CorpusCase.value("jmespath-jsonata-query", source, stepExpr(expr), document, expectedValue);
    }

    private static Node changesetProgram(Node changes) {
        return stepDo(list(
                op("$appendChanges", changes),
                op("$return", obj("changeset", op("$changeset", true), "events", list()))
        ));
    }

    private BexExecutionContext context(Node document, String scope) {
        return BexExecutionContext.builder()
                .document(new FrozenBexDocumentView(frozen(document), frozen(document), scope))
                .event(BexValues.nodeSnapshot(obj()))
                .currentContract(BexValues.nodeSnapshot(obj()))
                .gasLimit(1_000_000)
                .build();
    }

    private static Node pod(Node... containers) {
        return podWithSpec(obj("containers", list((Object[]) containers)));
    }

    private static Node podWithSpec(Node spec) {
        return obj("kind", "Pod", "metadata", obj("name", "pod", "namespace", "prod", "labels", obj("app", "demo")), "spec", spec);
    }

    private static Node container(String name, String image, Object... extra) {
        List<Object> pairs = new ArrayList<>();
        pairs.add("name");
        pairs.add(name);
        pairs.add("image");
        pairs.add(image);
        for (Object item : extra) {
            pairs.add(item);
        }
        return obj(pairs.toArray());
    }

    private static Node containers() {
        return listOrEmpty(doc("/spec/containers"));
    }

    private static Node defaultContainerPatch(String pathSuffix, Object value) {
        return flatMapIndexed(containers(), "c", "i",
                choose(not(exists(var("c", pathSuffix))),
                        list(patch("add", containerPath(pathSuffix), value)),
                        list()));
    }

    private static Node containerPath(String pathSuffix) {
        Object[] suffix = pathSuffixSegments(pathSuffix);
        Object[] segments = new Object[3 + suffix.length];
        segments[0] = "spec";
        segments[1] = "containers";
        segments[2] = var("i");
        System.arraycopy(suffix, 0, segments, 3, suffix.length);
        return pointerJoin(segments);
    }

    private static Object[] pathSuffixSegments(String pathSuffix) {
        String[] parts = pathSuffix.substring(1).split("/");
        Object[] out = new Object[parts.length];
        System.arraycopy(parts, 0, out, 0, parts.length);
        return out;
    }

    private static Node doc(String path) {
        return op("$document", path);
    }

    private static Node var(String name) {
        return op("$var", name);
    }

    private static Node var(String name, String path) {
        return op("$var", obj("name", name, "path", path));
    }

    private static Node listOrEmpty(Object expr) {
        return choose(isKind(expr, "list"), expr, list());
    }

    private static Node isKind(Object val, String kind) {
        return op("$isKind", obj("val", val, "kind", kind));
    }

    private static Node exists(Object expr) {
        return op("$exists", expr);
    }

    private static Node truthy(Object expr) {
        return op("$truthy", expr);
    }

    private static Node not(Object expr) {
        return op("$not", expr);
    }

    private static Node and(Object... exprs) {
        return op("$and", list(exprs));
    }

    private static Node or(Object... exprs) {
        return op("$or", list(exprs));
    }

    private static Node eq(Object left, Object right) {
        return op("$eq", list(left, right));
    }

    private static Node ne(Object left, Object right) {
        return op("$ne", list(left, right));
    }

    private static Node gt(Object left, Object right) {
        return op("$gt", list(left, right));
    }

    private static Node lt(Object left, Object right) {
        return op("$lt", list(left, right));
    }

    private static Node lte(Object left, Object right) {
        return op("$lte", list(left, right));
    }

    private static Node add(Object left, Object right) {
        return op("$add", list(left, right));
    }

    private static Node startsWith(Object text, Object prefix) {
        return op("$startsWith", list(text, prefix));
    }

    private static Node split(Object text, String separator) {
        return op("$split", obj("text", text, "separator", separator));
    }

    private static Node size(Object expr) {
        return op("$size", expr);
    }

    private static Node get(Object object, Object key) {
        return op("$get", obj("object", object, "key", key));
    }

    private static Node pointerGet(Object object, String path) {
        return op("$pointerGet", obj("object", object, "path", path));
    }

    private static Node hasKey(Object object, Object key) {
        return op("$hasKey", obj("object", object, "key", key));
    }

    private static Node includes(Object list, Object val) {
        return op("$includes", obj("list", list, "val", val));
    }

    private static Node choose(Object cond, Object thenExpr, Object elseExpr) {
        return op("$choose", obj("cond", cond, "then", thenExpr, "else", elseExpr));
    }

    private static Node some(Object in, String item, Object where) {
        return op("$some", obj("in", in, "item", item, "where", where));
    }

    private static Node someContainer(Object where) {
        return some(containers(), "c", where);
    }

    private static Node containerMissing(String path) {
        return someContainer(not(exists(var("c", path))));
    }

    private static Node filter(Object in, String item, Object where) {
        return op("$filter", obj("in", in, "item", item, "where", where));
    }

    private static Node map(Object in, String item, Object expr) {
        return op("$map", obj("in", in, "item", item, "expr", expr));
    }

    private static Node mapIndexed(Object in, String item, String index, Object expr) {
        return op("$map", obj("in", in, "item", item, "index", index, "expr", expr));
    }

    private static Node flatMap(Object in, String item, Object expr) {
        return op("$flatMap", obj("in", in, "item", item, "expr", expr));
    }

    private static Node flatMapIndexed(Object in, String item, String index, Object expr) {
        return op("$flatMap", obj("in", in, "item", item, "index", index, "expr", expr));
    }

    private static Node reduce(Object in, String acc, Object init, String item, Object expr) {
        return op("$reduce", obj("in", in, "acc", acc, "init", init, "item", item, "expr", expr));
    }

    private static Node join(Object list, String separator) {
        return op("$join", obj("list", list, "separator", separator));
    }

    private static Node pointerJoin(Object... segments) {
        return op("$pointerJoin", list(segments));
    }

    private static Node patch(String op, Object path) {
        return obj("op", op, "path", path);
    }

    private static Node patch(String op, Object path, Object val) {
        return obj("op", op, "path", path, "val", val);
    }

    private static Object p(String op, String path, Object val) {
        return m("op", op, "path", path, "val", val);
    }

    private static final class CorpusCase {
        private final String category;
        private final String source;
        private final Node program;
        private final Node document;
        private final String documentScope;
        private final Object expectedValue;
        private final Object expectedChangeset;

        private CorpusCase(String category, String source, Node program, Node document, String documentScope,
                           Object expectedValue, Object expectedChangeset) {
            this.category = category;
            this.source = source;
            this.program = program;
            this.document = document;
            this.documentScope = documentScope;
            this.expectedValue = expectedValue;
            this.expectedChangeset = expectedChangeset;
        }

        private static CorpusCase value(String category, String source, Node program, Node document, Object expectedValue) {
            return new CorpusCase(category, source, program, document, "/", expectedValue, null);
        }

        private static CorpusCase changes(String category, String source, Node program, Node document, String scope, Object expectedChangeset) {
            return new CorpusCase(category, source, program, document, scope, null, expectedChangeset);
        }
    }
}
