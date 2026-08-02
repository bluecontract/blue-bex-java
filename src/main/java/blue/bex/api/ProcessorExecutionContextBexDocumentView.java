package blue.bex.api;

import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.snapshot.FrozenNode;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.model.wire.JsonPointer;

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
        return exactAt(absolutePointer);
    }

    @Override
    public BexValue resolvedAt(String absolutePointer) {
        return exactAt(absolutePointer);
    }

    @Override
    public String currentScopePath() {
        String pointer = context.resolvePointer("");
        return pointer != null ? JsonPointer.canonicalize(pointer) : "/";
    }

    private BexValue exactAt(String absolutePointer) {
        FrozenNode canonical = context.canonicalFrozenAt(absolutePointer);
        FrozenNode resolved = context.resolvedFrozenAt(absolutePointer);
        return canonical != null || resolved != null
                ? BexValues.exact(canonical, resolved)
                : BexValues.undefined();
    }
}
