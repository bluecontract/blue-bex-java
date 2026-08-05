package blue.bex.buildlogic;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

/** Typed coordinates and dependency mode shared by BEX module builds. */
public abstract class LanguageDependencyModeExtension {
    public LanguageDependencyModeExtension(ObjectFactory objects) {
        getVersion().convention("3.1.0-rc.20");
        getCompositePropertyName().convention("blueLanguageCompositePath");
    }

    public abstract Property<String> getVersion();

    public abstract Property<String> getCompositePropertyName();

    public String coordinate(String artifact) {
        return "blue.language:" + artifact + ":" + getVersion().get();
    }
}
