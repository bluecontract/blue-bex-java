package blue.bex.value;

/** Read-only patch-entry projection consumed by the value layer. */
public interface BexPatchValueView {
    String op();
    String absolutePath();
    BexValue val();
}
