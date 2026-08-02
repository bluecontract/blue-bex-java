package blue.bex.type;

import blue.bex.BexSourcePath;
import blue.bex.gas.BexGasMeter;
import blue.bex.value.BexValue;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.FrozenNode;

/**
 * Compatibility facade for the cohesive {@link BexTypeMatcher} boundary.
 */
public final class BexBlueTypeMatcher {
    private final BexTypeMatcher delegate;

    public BexBlueTypeMatcher(BlueLanguage blue) {
        this.delegate = new BexTypeMatcher(blue);
    }

    public boolean matches(
            BexValue value,
            FrozenNode pattern,
            BexGasMeter gas,
            BexSourcePath sourcePath) {
        return delegate.matches(value, pattern, gas, sourcePath);
    }
}
