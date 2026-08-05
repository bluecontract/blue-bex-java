package blue.bex.compile;

import blue.bex.result.BexMetricsRecorder;

/** Compiler from frozen BEX Blue data to specialized runtime objects. */
final class BexCompiler {
    private final BexProgramCompiler delegate;

    BexCompiler(BexMetricsRecorder metrics, BexIntrinsicCatalog intrinsics) {
        this.delegate = new BexProgramCompiler(metrics, intrinsics);
    }

    /**
     * Compiles and permanently binds the program to every supplied
     * compilation-affecting identity.
     */
    BexCompiledProgram compile(
            BexCompilationInput source,
            String compilationEnvironmentIdentity) {
        return delegate.compileProgram(source, compilationEnvironmentIdentity);
    }
}
