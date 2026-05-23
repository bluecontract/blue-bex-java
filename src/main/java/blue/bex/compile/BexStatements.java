package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.BexSourcePath;
import blue.bex.result.BexPatchEntry;
import blue.bex.runtime.CompiledExpression;
import blue.bex.runtime.CompiledFrame;
import blue.bex.runtime.CompiledStatement;
import blue.bex.runtime.Control;
import blue.bex.value.BexValue;
import blue.bex.value.BexValues;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

abstract class Stmt implements CompiledStatement {
    @Override
    public final Control exec(CompiledFrame frame) {
        frame.runtime().metrics().incrementStatementExecutions();
        frame.runtime().gas().charge(frame.runtime().gas().schedule().statementBase);
        try {
            return doExec(frame);
        } catch (BexException ex) {
            BexSourcePath sourcePath = frame.sourcePath();
            if (sourcePath != null && !ex.sourcePath().isPresent()) {
                throw ex.withSourcePath(sourcePath);
            }
            throw ex;
        }
    }

    protected abstract Control doExec(CompiledFrame frame);
}

final class SourceStatement implements CompiledStatement {
    private final BexSourcePath sourcePath;
    private final CompiledStatement delegate;

    SourceStatement(BexSourcePath sourcePath, CompiledStatement delegate) {
        this.sourcePath = sourcePath;
        this.delegate = delegate;
    }

    @Override
    public Control exec(CompiledFrame frame) {
        BexSourcePath previous = frame.enter(sourcePath);
        try {
            return delegate.exec(frame);
        } catch (BexException ex) {
            if (!ex.sourcePath().isPresent()) {
                throw ex.withSourcePath(sourcePath);
            }
            throw ex;
        } finally {
            frame.restore(previous);
        }
    }
}

class LetStatement extends Stmt {
    private final int slot;
    private final CompiledExpression expr;

    LetStatement(int slot, CompiledExpression expr) {
        this.slot = slot;
        this.expr = expr;
    }

    @Override
    protected Control doExec(CompiledFrame frame) {
        frame.set(slot, expr.eval(frame));
        return Control.CONTINUE;
    }
}

final class SetStatement extends LetStatement {
    SetStatement(int slot, CompiledExpression expr) {
        super(slot, expr);
    }
}

final class IfStatement extends Stmt {
    private final CompiledExpression cond;
    private final List<CompiledStatement> thenStatements;
    private final List<CompiledStatement> elseStatements;

    IfStatement(CompiledExpression cond, List<CompiledStatement> thenStatements, List<CompiledStatement> elseStatements) {
        this.cond = cond;
        this.thenStatements = thenStatements;
        this.elseStatements = elseStatements;
    }

    @Override
    protected Control doExec(CompiledFrame frame) {
        List<CompiledStatement> chosen = BexValues.truthy(cond.eval(frame)) ? thenStatements : elseStatements;
        for (CompiledStatement statement : chosen) {
            if (statement.exec(frame) == Control.RETURN) return Control.RETURN;
        }
        return Control.CONTINUE;
    }
}

final class ForEachStatement extends Stmt {
    private final CompiledExpression input;
    private final int itemSlot;
    private final int keySlot;
    private final int indexSlot;
    private final List<CompiledStatement> body;

    ForEachStatement(CompiledExpression input, int itemSlot, int keySlot, int indexSlot, List<CompiledStatement> body) {
        this.input = input;
        this.itemSlot = itemSlot;
        this.keySlot = keySlot;
        this.indexSlot = indexSlot;
        this.body = body;
    }

    @Override
    protected Control doExec(CompiledFrame frame) {
        BexValue value = input.eval(frame);
        if (value.isObject()) {
            for (String key : value.keys()) {
                frame.runtime().metrics().incrementLoopIterations();
                frame.runtime().gas().charge(frame.runtime().gas().schedule().forEachItem);
                if (keySlot >= 0) {
                    frame.set(keySlot, BexValues.scalar(key));
                    frame.set(itemSlot, value.get(key));
                } else {
                    Map<String, BexValue> entry = new LinkedHashMap<>();
                    entry.put("key", BexValues.scalar(key));
                    entry.put("val", value.get(key));
                    frame.set(itemSlot, BexValues.map(entry));
                }
                if (indexSlot >= 0) {
                    frame.set(indexSlot, BexValues.undefined());
                }
                for (CompiledStatement statement : body) {
                    if (statement.exec(frame) == Control.RETURN) return Control.RETURN;
                }
            }
        } else if (value.isList()) {
            for (int i = 0; i < value.size(); i++) {
                frame.runtime().metrics().incrementLoopIterations();
                frame.runtime().gas().charge(frame.runtime().gas().schedule().forEachItem);
                frame.set(itemSlot, value.get(String.valueOf(i)));
                if (indexSlot >= 0) {
                    frame.set(indexSlot, BexValues.scalar(BigInteger.valueOf(i)));
                }
                if (keySlot >= 0) {
                    frame.set(keySlot, BexValues.undefined());
                }
                for (CompiledStatement statement : body) {
                    if (statement.exec(frame) == Control.RETURN) return Control.RETURN;
                }
            }
        } else {
            throw new BexException("$forEach input must be list or object");
        }
        return Control.CONTINUE;
    }
}

final class BexStatementEffects {
    private BexStatementEffects() {
    }

    static void appendChange(CompiledFrame frame, BexPatchEntry entry) {
        frame.runtime().gas().chargeValue(frame.runtime().gas().schedule().appendChangeBase, entry.val());
        frame.accumulator().appendChange(entry);
    }

    static void appendEvent(CompiledFrame frame, BexValue value) {
        if (value.isUndefined()) {
            throw new BexException("Undefined cannot be emitted as an event");
        }
        frame.runtime().gas().chargeValue(frame.runtime().gas().schedule().appendEventBase, value);
        frame.accumulator().appendEvent(value);
    }
}

final class AppendChangeStatement extends Stmt {
    private final TextOperand op;
    private final PointerOperand pointer;
    private final CompiledExpression val;

    AppendChangeStatement(TextOperand op, PointerOperand pointer, CompiledExpression val) {
        this.op = op;
        this.pointer = pointer;
        this.val = val;
    }

    @Override
    protected Control doExec(CompiledFrame frame) {
        String operation = op.get(frame);
        boolean requiresVal = BexPatchEntryParser.requiresValue(operation);
        BexValue value = BexValues.undefined();
        if (requiresVal) {
            if (val == null) {
                throw new BexException("Patch op " + operation + " requires val");
            }
            value = val.eval(frame);
        }
        BexStatementEffects.appendChange(frame,
                BexPatchEntryParser.fromFields(frame, operation, pointer.authored(frame), val != null, value));
        return Control.CONTINUE;
    }
}

final class AppendChangesStatement extends Stmt {
    private final CompiledExpression expr;

    AppendChangesStatement(CompiledExpression expr) {
        this.expr = expr;
    }

    @Override
    protected Control doExec(CompiledFrame frame) {
        BexValue list = expr.eval(frame);
        if (!list.isList()) throw new BexException("$appendChanges requires a list");
        for (int i = 0; i < list.size(); i++) {
            BexStatementEffects.appendChange(frame,
                    BexPatchEntryParser.fromValue(frame, list.get(String.valueOf(i))));
        }
        return Control.CONTINUE;
    }
}

final class AppendEventStatement extends Stmt {
    private final CompiledExpression expr;

    AppendEventStatement(CompiledExpression expr) {
        this.expr = expr;
    }

    @Override
    protected Control doExec(CompiledFrame frame) {
        BexStatementEffects.appendEvent(frame, expr.eval(frame));
        return Control.CONTINUE;
    }
}

final class AppendEventsStatement extends Stmt {
    private final CompiledExpression expr;

    AppendEventsStatement(CompiledExpression expr) {
        this.expr = expr;
    }

    @Override
    protected Control doExec(CompiledFrame frame) {
        BexValue list = expr.eval(frame);
        if (!list.isList()) throw new BexException("$appendEvents requires a list");
        for (int i = 0; i < list.size(); i++) {
            BexStatementEffects.appendEvent(frame, list.get(String.valueOf(i)));
        }
        return Control.CONTINUE;
    }
}

final class CallStatement extends Stmt {
    private final CompiledExpression call;

    CallStatement(CompiledExpression call) {
        this.call = call;
    }

    @Override
    protected Control doExec(CompiledFrame frame) {
        call.eval(frame);
        return Control.CONTINUE;
    }
}

final class ReturnStatement extends Stmt {
    private final CompiledExpression expr;

    ReturnStatement(CompiledExpression expr) {
        this.expr = expr;
    }

    @Override
    protected Control doExec(CompiledFrame frame) {
        frame.returnValue(expr != null ? expr.eval(frame) : frame.runtime().defaultResultValue());
        return Control.RETURN;
    }
}

final class FailStatement extends Stmt {
    private final CompiledExpression message;

    FailStatement(CompiledExpression message) {
        this.message = message;
    }

    @Override
    protected Control doExec(CompiledFrame frame) {
        throw new BexException(message.eval(frame).asText());
    }
}
