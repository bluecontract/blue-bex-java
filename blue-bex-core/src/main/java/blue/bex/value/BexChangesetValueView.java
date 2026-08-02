package blue.bex.value;

import java.util.List;

/** Read-only ordered changeset projection consumed by the value layer. */
public interface BexChangesetValueView {
    List<? extends BexPatchValueView> entries();
}
