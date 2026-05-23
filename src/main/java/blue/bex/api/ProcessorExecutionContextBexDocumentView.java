package blue.bex.api;

import blue.bex.value.BexValue;
import blue.bex.value.BexValues;
import blue.language.model.Node;
import blue.language.processor.ProcessorExecutionContext;
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
        return documentAt(absolutePointer);
    }

    @Override
    public BexValue resolvedAt(String absolutePointer) {
        return documentAt(absolutePointer);
    }

    @Override
    public String currentScopePath() {
        String pointer = context.resolvePointer("");
        return pointer != null ? JsonPointer.canonicalize(pointer) : "/";
    }

    private BexValue documentAt(String absolutePointer) {
        Node selected = context.documentAt(absolutePointer);
        return selected != null ? BexValues.nodeSnapshot(selected) : BexValues.undefined();
    }
}
