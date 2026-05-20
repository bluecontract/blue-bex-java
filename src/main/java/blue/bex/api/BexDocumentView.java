package blue.bex.api;

import blue.bex.value.BexValue;

/**
 * Immutable document access boundary for BEX execution.
 *
 * <p>Implementations own pointer resolution and the current scope path. BEX
 * document reads call either {@link #canonicalAt(String)} or
 * {@link #resolvedAt(String)} with an absolute pointer.</p>
 */
public interface BexDocumentView {
    String resolvePointer(String authoredPointer);
    BexValue canonicalAt(String absolutePointer);
    BexValue resolvedAt(String absolutePointer);
    String currentScopePath();
}
