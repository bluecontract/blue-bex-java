package blue.bex.api;

import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;

import java.util.Objects;

/**
 * Adapter from blue-language-java processor execution context to BEX document view.
 */
public final class ProcessorExecutionContextBexDocumentView implements BexDocumentView {
    private final ProcessorExecutionContext context;

    public ProcessorExecutionContextBexDocumentView(ProcessorExecutionContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public String resolvePointer(String authoredPointer) {
        return context.resolvePointer(authoredPointer);
    }

    @Override
    public BexValue canonicalAt(String absolutePointer) {
        FrozenNode selected = context.canonicalFrozenAt(absolutePointer);
        if (selected != null) {
            return BexValues.frozen(selected);
        }
        FrozenNode root = context.canonicalFrozenAt("/");
        return root != null ? BexValues.frozen(root).at(JsonPointer.split(absolutePointer)) : BexValues.undefined();
    }

    @Override
    public BexValue resolvedAt(String absolutePointer) {
        FrozenNode selected = context.resolvedFrozenAt(absolutePointer);
        if (selected != null) {
            return BexValues.frozen(selected);
        }
        FrozenNode root = context.resolvedFrozenAt("/");
        return root != null ? BexValues.frozen(root).at(JsonPointer.split(absolutePointer)) : BexValues.undefined();
    }

    @Override
    public String currentScopePath() {
        return context.scopePath();
    }
}
