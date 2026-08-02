package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.pointer.BexPointer;
import blue.bex.value.BexValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

interface TextOperand {
    String get(CompiledFrame frame);
}

interface PointerOperand {
    ResolvedPointer resolve(CompiledFrame frame);

    default String authored(CompiledFrame frame) {
        return resolve(frame).authored();
    }

    default String absolute(CompiledFrame frame) {
        return resolve(frame).absolute();
    }

    default List<String> segments(CompiledFrame frame) {
        return resolve(frame).segments();
    }
}

final class ResolvedPointer {
    private final String authored;
    private final String absolute;
    private final List<String> segments;

    ResolvedPointer(String authored, String absolute, List<String> segments) {
        this.authored = authored;
        this.absolute = absolute;
        this.segments = Collections.unmodifiableList(
                new ArrayList<>(segments));
    }

    String authored() {
        return authored;
    }

    String absolute() {
        return absolute;
    }

    List<String> segments() {
        return segments;
    }
}

final class StaticTextExpr implements TextOperand {
    private final String text;

    StaticTextExpr(String text) {
        this.text = text;
    }

    @Override
    public String get(CompiledFrame frame) {
        return text;
    }
}

final class DynamicTextExpr implements TextOperand {
    private final CompiledExpression expr;
    private final String label;

    DynamicTextExpr(CompiledExpression expr, String label) {
        this.expr = expr;
        this.label = label;
    }

    @Override
    public String get(CompiledFrame frame) {
        return TextOperands.text(expr.eval(frame), label);
    }
}

final class StaticPointerOperand implements PointerOperand {
    private final String authored;
    private final BexPointer pointer;
    private final boolean absolute;

    private StaticPointerOperand(String authored) {
        this.authored = authored;
        this.absolute = authored != null && authored.startsWith("/");
        this.pointer = absolute ? BexPointer.parse(authored) : null;
    }

    static StaticPointerOperand of(String authored) {
        return new StaticPointerOperand(authored == null ? "/" : authored);
    }

    static StaticPointerOperand absolute(String pointer) {
        return new StaticPointerOperand(pointer);
    }

    @Override
    public ResolvedPointer resolve(CompiledFrame frame) {
        if (absolute) {
            return new ResolvedPointer(authored, pointer.text(), pointer.segments());
        }
        String resolved = frame.machine().resolvePointer(authored);
        return new ResolvedPointer(authored, resolved,
                frame.machine().parseDynamicPointer(resolved));
    }
}

final class DynamicPointerOperand implements PointerOperand {
    private final CompiledExpression expr;

    DynamicPointerOperand(CompiledExpression expr) {
        this.expr = expr;
    }

    @Override
    public ResolvedPointer resolve(CompiledFrame frame) {
        String authored = PointerOperands.pointerText(expr.eval(frame));
        String absolute = frame.machine().resolvePointer(authored);
        return new ResolvedPointer(authored, absolute,
                frame.machine().parseDynamicPointer(absolute));
    }
}

final class StaticValuePointerOperand implements PointerOperand {
    private final BexPointer pointer;

    private StaticValuePointerOperand(String authored) {
        this.pointer = BexPointer.parse(normalize(authored));
    }

    static StaticValuePointerOperand of(String authored) {
        return new StaticValuePointerOperand(authored);
    }

    @Override
    public ResolvedPointer resolve(CompiledFrame frame) {
        return new ResolvedPointer(pointer.text(), pointer.text(), pointer.segments());
    }

    static String normalize(String authored) {
        if (authored == null || authored.isEmpty()) {
            return "/";
        }
        return authored.startsWith("/") ? authored : "/" + authored;
    }
}

final class DynamicValuePointerOperand implements PointerOperand {
    private final CompiledExpression expr;

    DynamicValuePointerOperand(CompiledExpression expr) {
        this.expr = expr;
    }

    @Override
    public ResolvedPointer resolve(CompiledFrame frame) {
        String authored = StaticValuePointerOperand.normalize(
                PointerOperands.pointerText(expr.eval(frame)));
        String absolute = frame.machine().canonicalPointer(authored);
        return new ResolvedPointer(authored, absolute,
                frame.machine().parseDynamicPointer(absolute));
    }
}

final class PointerOperands {
    private PointerOperands() {
    }

    static String pointerText(BexValue value) {
        if (value == null || value.isUndefined() || value.isNull()) {
            throw new BexException("Pointer operand cannot be null or undefined");
        }
        return value.asText();
    }
}

final class TextOperands {
    private TextOperands() {
    }

    static String text(BexValue value, String label) {
        if (value == null || value.isUndefined() || value.isNull()) {
            throw new BexException(label + " cannot be null or undefined");
        }
        return value.asText();
    }
}
