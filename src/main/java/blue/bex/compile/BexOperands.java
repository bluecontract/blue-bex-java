package blue.bex.compile;

import blue.bex.pointer.BexPointer;
import blue.bex.runtime.CompiledExpression;
import blue.bex.runtime.CompiledFrame;

import java.util.List;

interface TextOperand {
    String get(CompiledFrame frame);
}

interface PointerOperand {
    String authored(CompiledFrame frame);
    String absolute(CompiledFrame frame);
    List<String> segments(CompiledFrame frame);
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

    DynamicTextExpr(CompiledExpression expr) {
        this.expr = expr;
    }

    @Override
    public String get(CompiledFrame frame) {
        return expr.eval(frame).asText();
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
    public String authored(CompiledFrame frame) {
        return authored;
    }

    @Override
    public String absolute(CompiledFrame frame) {
        return absolute ? pointer.text() : frame.runtime().resolvePointer(authored);
    }

    @Override
    public List<String> segments(CompiledFrame frame) {
        if (absolute) {
            return pointer.segments();
        }
        return frame.runtime().parseDynamicPointer(absolute(frame));
    }
}

final class DynamicPointerOperand implements PointerOperand {
    private final CompiledExpression expr;

    DynamicPointerOperand(CompiledExpression expr) {
        this.expr = expr;
    }

    @Override
    public String authored(CompiledFrame frame) {
        return expr.eval(frame).asText();
    }

    @Override
    public String absolute(CompiledFrame frame) {
        return frame.runtime().resolvePointer(authored(frame));
    }

    @Override
    public List<String> segments(CompiledFrame frame) {
        return frame.runtime().parseDynamicPointer(absolute(frame));
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
    public String authored(CompiledFrame frame) {
        return pointer.text();
    }

    @Override
    public String absolute(CompiledFrame frame) {
        return pointer.text();
    }

    @Override
    public List<String> segments(CompiledFrame frame) {
        return pointer.segments();
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
    public String authored(CompiledFrame frame) {
        return StaticValuePointerOperand.normalize(expr.eval(frame).asText());
    }

    @Override
    public String absolute(CompiledFrame frame) {
        return frame.runtime().canonicalPointer(authored(frame));
    }

    @Override
    public List<String> segments(CompiledFrame frame) {
        return frame.runtime().parseDynamicPointer(absolute(frame));
    }
}
