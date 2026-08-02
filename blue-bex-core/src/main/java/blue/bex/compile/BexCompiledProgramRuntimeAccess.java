package blue.bex.compile;

import blue.bex.value.BexValue;

/**
 * Internal bridge that executes an opaque compiled-program handle.
 *
 * <p>Public visibility is required only across BEX implementation packages;
 * this type is classified as internal implementation and is not a host SPI.</p>
 */
public final class BexCompiledProgramRuntimeAccess {
    private BexCompiledProgramRuntimeAccess() {
    }

    public static BexValue execute(
            BexCompiledProgram program,
            BexExecutionMachine machine) {
        return program.execute(machine);
    }

    /** Validates the complete opaque compile/cache binding for engine use. */
    public static boolean matchesCompilationKey(
            BexCompiledProgram program,
            BexCompiledProgramKey expected) {
        return program.matchesCompilationKey(expected);
    }
}
