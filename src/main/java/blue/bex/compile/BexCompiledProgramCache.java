package blue.bex.compile;

public interface BexCompiledProgramCache {
    BexCompiledProgram get(BexCompiledProgramKey key);
    void put(BexCompiledProgramKey key, BexCompiledProgram program);
}
