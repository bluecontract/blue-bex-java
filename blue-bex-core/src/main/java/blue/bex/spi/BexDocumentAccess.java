package blue.bex.spi;

import blue.bex.value.BexValue;

/** Read-only canonical/resolved document access used below the public API. */
public interface BexDocumentAccess {
    String resolvePointer(String authoredPointer);
    BexValue canonicalAt(String absolutePointer);
    BexValue resolvedAt(String absolutePointer);
    String currentScopePath();
}
