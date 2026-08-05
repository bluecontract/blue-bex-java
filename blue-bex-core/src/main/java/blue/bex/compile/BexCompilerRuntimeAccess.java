package blue.bex.compile;

import blue.bex.result.BexMetricsRecorder;

/** Internal bridge for engine-owned compilation diagnostics and identity. */
public final class BexCompilerRuntimeAccess {
    private BexCompilerRuntimeAccess() {
    }

    public static BexCompiledProgram compile(
            BexCompilationInput source,
            BexMetricsRecorder metrics,
            BexIntrinsicCatalog intrinsics,
            String compilationEnvironmentIdentity) {
        return new BexCompiler(metrics, intrinsics).compile(
                source, compilationEnvironmentIdentity);
    }
}
