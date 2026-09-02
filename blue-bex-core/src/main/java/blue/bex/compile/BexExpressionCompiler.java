package blue.bex.compile;

import blue.bex.BexException;
import blue.bex.BexSourcePath;
import blue.bex.value.BexValue;
import blue.bex.value.BexUnicodeOrder;
import blue.bex.value.BexValues;
import blue.bex.result.BexMetricsRecorder;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/** Expression recognition, validation, and IR construction. */
abstract class BexExpressionCompiler extends BexCompilerSupport {
    BexExpressionCompiler(BexMetricsRecorder metrics, BexIntrinsicCatalog intrinsics) {
        super(metrics, intrinsics);
    }

    @Override
    final CompiledExpression compileExpr(FrozenNode node, CompileScope scope, String pointer) {
        if (node == null) {
            return sourceExpr(currentFunction, pointer, null, new LiteralExpr(BexValues.nullValue()));
        }
        rejectBexInStaticBlueDefinitionFields(node, pointer);
        if (isExpressionOperatorShape(node)) {
            String op = node.getProperties().keySet().iterator().next();
            FrozenNode body = node.getProperties().values().iterator().next();
            BexSourcePath sourcePath = BexSourcePath.of(currentFunction, pointer + "/" + escape(op), op);
            try {
                return new SourceExpr(sourcePath, compileOperator(op, body, scope, pointer + "/" + escape(op)));
            } catch (BexException ex) {
                throw ex.withSourcePath(sourcePath);
            }
        }
        if (node.isEmptyNode()) {
            BexValue value = node.isInlineValue()
                    ? BexValues.nullValue()
                    : BexValues.map(Collections.<String, BexValue>emptyMap());
            return sourceExpr(currentFunction, pointer, null,
                    new LiteralExpr(value));
        }
        if (isScalarNode(node)) {
            return sourceExpr(currentFunction, pointer, null,
                    new TransientLiteralExpr(node.getValue()));
        }
        if (node.getItems() != null && !hasLanguageFields(node)) {
            List<CompiledExpression> items = new ArrayList<>();
            for (int i = 0; i < node.getItems().size(); i++) {
                items.add(compileExpr(node.getItems().get(i), scope, pointer + "/" + i));
            }
            return sourceExpr(currentFunction, pointer, null, new ListExpr(items));
        }
        if (node.getProperties() != null || hasLanguageFields(node)) {
            Map<String, CompiledExpression> fields = new LinkedHashMap<>();
            addMetadataFields(fields, node, scope, pointer);
            if (node.getItems() != null) {
                List<CompiledExpression> items = new ArrayList<>();
                for (int i = 0; i < node.getItems().size(); i++) {
                    items.add(compileExpr(node.getItems().get(i), scope, pointer + "/" + i));
                }
                fields.put("items", new ListExpr(items));
            }
            if (node.getProperties() != null) {
                for (String key : BexUnicodeOrder.sortedCopy(node.getProperties().keySet())) {
                    fields.put(key, compileExpr(node.getProperties().get(key), scope,
                            pointer + "/" + escape(key)));
                }
            }
            return sourceExpr(currentFunction, pointer, null, new ObjectExpr(fields));
        }
        return sourceExpr(currentFunction, pointer, null,
                new TransientLiteralExpr(node.getValue()));
    }

    final CompiledExpression compileOperator(String op, FrozenNode body, CompileScope scope, String pointer) {
        if (!BexOperatorCatalog.supportsExpression(op)) {
            throw new BexException("Unknown expression operator: " + op);
        }
        validateExpressionBody(op, body);
        if ("$literal".equals(op)) return new LiteralExpr(BexValues.frozen(body));
        if ("$null".equals(op)) return new LiteralExpr(BexValues.nullValue());
        if ("$emptyObject".equals(op)) return new LiteralExpr(BexValues.map(Collections.<String, BexValue>emptyMap()));
        if ("$emptyList".equals(op)) return new LiteralExpr(BexValues.list(Collections.<BexValue>emptyList()));
        if ("$document".equals(op)) return documentExpr(body, scope, pointer);
        if ("$binding".equals(op)) return bindingExpr(body, scope, pointer);
        if ("$event".equals(op)) return contextPointerExpr(body, scope, ContextKind.EVENT, pointer);
        if ("$processingEvent".equals(op)) return contextPointerExpr(body, scope, ContextKind.PROCESSING_EVENT, pointer);
        if ("$steps".equals(op)) return stepsExpr(body, scope, pointer);
        if ("$currentContract".equals(op)) return contextPointerExpr(body, scope, ContextKind.CURRENT_CONTRACT, pointer);
        if ("$var".equals(op)) return varExpr(body, scope, pointer);
        if ("$const".equals(op)) {
            String name = constName(body);
            if (!constants.containsKey(name)) {
                throw new BexException("Unknown constant: " + name);
            }
            return new ConstExpr(name, pathOperandOrNull(body, scope, pointer));
        }
        if ("$get".equals(op)) return new GetExpr(compileExpr(required(prop(body, "object"), "$get.object"), scope, pointer + "/object"), textOrExpr(required(prop(body, "key"), "$get.key"), scope, null, pointer + "/key"));
        if ("$changeset".equals(op)) return new ChangesetExpr();
        if ("$events".equals(op)) return new EventsExpr();
        if ("$resultValue".equals(op)) return new ResultValueExpr(pointerOperand(body, scope, pointer));
        if ("$unwrap".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.UNWRAP);
        if ("$is".equals(op)) return isExpr(body, scope, pointer);
        if ("$text".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.TEXT);
        if ("$integer".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.INTEGER);
        if ("$number".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.NUMBER);
        if ("$boolean".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.BOOLEAN);
        if ("$object".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.OBJECT);
        if ("$list".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.LIST);
        if ("$concat".equals(op)) return new VariadicExpr(compileExprList(body, scope, pointer), VariadicOp.CONCAT);
        if ("$pointerJoin".equals(op)) return new PointerJoinExpr(compileExprList(body, scope, pointer));
        if ("$join".equals(op)) return new JoinExpr(compileExpr(required(prop(body, "list"), "$join.list"), scope, pointer + "/list"), compileExpr(required(prop(body, "separator"), "$join.separator"), scope, pointer + "/separator"));
        if ("$split".equals(op)) return new SplitExpr(compileExpr(required(prop(body, "text"), "$split.text"), scope, pointer + "/text"), compileExpr(required(prop(body, "separator"), "$split.separator"), scope, pointer + "/separator"), prop(body, "limit") != null ? compileExpr(prop(body, "limit"), scope, pointer + "/limit") : null);
        if ("$startsWith".equals(op)) return new BinaryTextExpr(compileExprList(body, scope, pointer), BinaryTextOp.STARTS_WITH);
        if ("$sliceAfter".equals(op)) return new BinaryTextExpr(compileExprList(body, scope, pointer), BinaryTextOp.SLICE_AFTER);
        if ("$eq".equals(op)) return new CompareExpr(compileExprList(body, scope, pointer), CompareOp.EQ);
        if ("$ne".equals(op)) return new CompareExpr(compileExprList(body, scope, pointer), CompareOp.NE);
        if ("$gt".equals(op)) return new CompareExpr(compileExprList(body, scope, pointer), CompareOp.GT);
        if ("$gte".equals(op)) return new CompareExpr(compileExprList(body, scope, pointer), CompareOp.GTE);
        if ("$lt".equals(op)) return new CompareExpr(compileExprList(body, scope, pointer), CompareOp.LT);
        if ("$lte".equals(op)) return new CompareExpr(compileExprList(body, scope, pointer), CompareOp.LTE);
        if ("$and".equals(op)) return new LogicalExpr(compileExprList(body, scope, pointer), true);
        if ("$or".equals(op)) return new LogicalExpr(compileExprList(body, scope, pointer), false);
        if ("$not".equals(op)) return new NotExpr(compileExpr(body, scope, pointer));
        if ("$truthy".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.TRUTHY);
        if ("$empty".equals(op) || "$isEmpty".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.EMPTY);
        if ("$exists".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.EXISTS);
        if ("$kind".equals(op)) return new KindExpr(compileExpr(body, scope, pointer));
        if ("$isKind".equals(op)) return new IsKindExpr(compileExpr(required(prop(body, "val"), "$isKind.val"), scope, pointer + "/val"), kindSet(required(prop(body, "kind"), "$isKind.kind")));
        if ("$nodeBlueId".equals(op)) return new NodeBlueIdExpr(compileExpr(body, scope, pointer));
        if ("$coalesce".equals(op)) return new CoalesceExpr(compileExprList(body, scope, pointer));
        if ("$default".equals(op)) return new CoalesceExpr(compileExprList(body, scope, pointer));
        if ("$add".equals(op)) return new NumericExpr(compileExprList(body, scope, pointer), NumericOp.ADD);
        if ("$subtract".equals(op)) return new NumericExpr(compileExprList(body, scope, pointer), NumericOp.SUBTRACT);
        if ("$multiply".equals(op)) return new NumericExpr(compileExprList(body, scope, pointer), NumericOp.MULTIPLY);
        if ("$divide".equals(op)) return new NumericExpr(compileExprList(body, scope, pointer), NumericOp.DIVIDE);
        if ("$keys".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.KEYS);
        if ("$entries".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.ENTRIES);
        if ("$size".equals(op)) return new UnaryExpr(compileExpr(body, scope, pointer), UnaryOp.SIZE);
        if ("$listGet".equals(op)) return new ListGetExpr(compileExpr(required(prop(body, "list"), "$listGet.list"), scope, pointer + "/list"), compileExpr(required(prop(body, "index"), "$listGet.index"), scope, pointer + "/index"), prop(body, "default") != null ? compileExpr(prop(body, "default"), scope, pointer + "/default") : null);
        if ("$listConcat".equals(op)) return new VariadicExpr(compileExprList(body, scope, pointer), VariadicOp.LIST_CONCAT);
        if ("$merge".equals(op)) return new VariadicExpr(compileExprList(body, scope, pointer), VariadicOp.MERGE);
        if ("$objectSet".equals(op)) return new ObjectSetExpr(compileExpr(required(prop(body, "object"), "$objectSet.object"), scope, pointer + "/object"), textOrExpr(required(prop(body, "key"), "$objectSet.key"), scope, null, pointer + "/key"), compileExpr(required(prop(body, "val"), "$objectSet.val"), scope, pointer + "/val"));
        if ("$pointerGet".equals(op)) return new PointerGetExpr(compileExpr(required(prop(body, "object"), "$pointerGet.object"), scope, pointer + "/object"), valuePointerOperand(required(prop(body, "path"), "$pointerGet.path"), scope, pointer + "/path"), prop(body, "default") != null ? compileExpr(prop(body, "default"), scope, pointer + "/default") : null);
        if ("$pointerSet".equals(op)) return new PointerSetExpr(compileExpr(required(prop(body, "object"), "$pointerSet.object"), scope, pointer + "/object"), textOrExpr(prop(body, "op"), scope, "set", pointer + "/op"), valuePointerOperand(required(prop(body, "path"), "$pointerSet.path"), scope, pointer + "/path"), prop(body, "val") != null ? compileExpr(prop(body, "val"), scope, pointer + "/val") : null);
        if ("$map".equals(op)) return collectionExpr(body, scope, pointer, CollectionOp.MAP, "expr");
        if ("$filter".equals(op)) return collectionExpr(body, scope, pointer, CollectionOp.FILTER, "where");
        if ("$flatMap".equals(op)) return collectionExpr(body, scope, pointer, CollectionOp.FLAT_MAP, "expr");
        if ("$reduce".equals(op)) return reduceExpr(body, scope, pointer);
        if ("$some".equals(op)) return collectionExpr(body, scope, pointer, CollectionOp.SOME, "where");
        if ("$find".equals(op)) return collectionExpr(body, scope, pointer, CollectionOp.FIND, "where");
        if ("$findEntry".equals(op)) return collectionExpr(body, scope, pointer, CollectionOp.FIND_ENTRY, "where");
        if ("$includes".equals(op)) return new IncludesExpr(compileExpr(required(prop(body, "list"), "$includes.list"), scope, pointer + "/list"),
                compileExpr(required(prop(body, "val"), "$includes.val"), scope, pointer + "/val"));
        if ("$hasKey".equals(op)) return new HasKeyExpr(compileExpr(required(prop(body, "object"), "$hasKey.object"), scope, pointer + "/object"),
                textOrExpr(required(prop(body, "key"), "$hasKey.key"), scope, null, pointer + "/key"));
        if ("$objectFromEntries".equals(op)) return new ObjectFromEntriesExpr(compileExpr(body, scope, pointer));
        if ("$intrinsic".equals(op)) return intrinsicExpr(body, scope, pointer);
        if ("$fail".equals(op)) return new FailExpr(failMessageExpr(body, scope, pointer));
        if ("$choose".equals(op)) return new ChooseExpr(compileExpr(required(prop(body, "cond"), "$choose.cond"), scope, pointer + "/cond"), compileExpr(required(prop(body, "then"), "$choose.then"), scope, pointer + "/then"), prop(body, "else") != null ? compileExpr(prop(body, "else"), scope, pointer + "/else") : new LiteralExpr(BexValues.undefined()));
        if ("$call".equals(op)) return compileCall(body, scope, pointer);
        throw new BexException("Catalogued expression operator has no compiler implementation: " + op);
    }

    CompiledExpression failMessageExpr(FrozenNode body,
                                               CompileScope scope,
                                               String pointer) {
        boolean messageWrapper = body != null
                && body.getProperties() != null
                && hasExplicitProperty(body, "message");
        return compileExpr(messageWrapper ? explicitProp(body, "message") : body,
                scope,
                messageWrapper ? pointer + "/message" : pointer);
    }

    void validateExpressionBody(String op, FrozenNode body) {
        if ("$concat".equals(op)
                || "$pointerJoin".equals(op)
                || "$listConcat".equals(op)
                || "$merge".equals(op)
                || "$and".equals(op)
                || "$or".equals(op)
                || "$coalesce".equals(op)
                || "$default".equals(op)) {
            requireListBody(body, op);
            return;
        }
        if ("$eq".equals(op)
                || "$ne".equals(op)
                || "$gt".equals(op)
                || "$gte".equals(op)
                || "$lt".equals(op)
                || "$lte".equals(op)
                || "$startsWith".equals(op)
                || "$sliceAfter".equals(op)) {
            requireListArity(body, op, 2);
            return;
        }
        if ("$add".equals(op)
                || "$subtract".equals(op)
                || "$multiply".equals(op)
                || "$divide".equals(op)) {
            requireListBody(body, op);
            if (body.getItems().isEmpty()) {
                throw new BexException(op + " requires at least one operand");
            }
            return;
        }
        if ("$get".equals(op)) {
            requireObjectBody(body, op, "object", "key");
        } else if ("$is".equals(op)) {
            requireObjectBody(body, op, "node", "pattern");
        } else if ("$isKind".equals(op)) {
            requireObjectBody(body, op, "val", "kind");
        } else if ("$join".equals(op)) {
            requireObjectBody(body, op, "list", "separator");
        } else if ("$split".equals(op)) {
            requireObjectBody(body, op, "text", "separator", "limit");
        } else if ("$listGet".equals(op)) {
            requireObjectBody(body, op, "list", "index", "default");
        } else if ("$objectSet".equals(op)) {
            requireObjectBody(body, op, "object", "key", "val");
        } else if ("$pointerGet".equals(op)) {
            requireObjectBody(body, op, "object", "path", "default");
        } else if ("$pointerSet".equals(op)) {
            requireObjectBody(body, op, "object", "path", "op", "val");
        } else if ("$map".equals(op) || "$flatMap".equals(op)) {
            requireObjectBody(body, op, "in", "item", "key", "index", "expr");
        } else if ("$filter".equals(op)
                || "$some".equals(op)
                || "$find".equals(op)
                || "$findEntry".equals(op)) {
            requireObjectBody(body, op, "in", "item", "key", "index", "where");
        } else if ("$reduce".equals(op)) {
            requireObjectBody(body, op, "in", "acc", "init", "item", "key", "index", "expr");
        } else if ("$includes".equals(op)) {
            requireObjectBody(body, op, "list", "val");
        } else if ("$hasKey".equals(op)) {
            requireObjectBody(body, op, "object", "key");
        } else if ("$choose".equals(op)) {
            requireObjectBody(body, op, "cond", "then", "else");
        } else if ("$call".equals(op)) {
            requireObjectBody(body, op, "function", "args");
        } else if ("$intrinsic".equals(op)) {
            requireObjectNode(body, op);
        } else if ("$binding".equals(op)) {
            if (!isScalarBody(body)) {
                requireObjectBody(body, op, "name", "path");
            }
        } else if ("$steps".equals(op)) {
            if (!isScalarBody(body)) {
                requireObjectBody(body, op, "step", "path");
            }
        } else if ("$var".equals(op) || "$const".equals(op)) {
            if (!isScalarBody(body)) {
                requireObjectBody(body, op, "name", "path");
            }
        } else if ("$document".equals(op)
                && body != null
                && hasAuthoredField(body, "path")
                && !isExpressionOperatorShape(body)) {
            requireObjectBody(body, op, "path", "view");
        }
    }


    void requireListBody(FrozenNode body, String op) {
        if (body == null || body.getItems() == null || hasNonListPayload(body)) {
            throw new BexException(op + " expects a list body");
        }
    }

    void requireListArity(FrozenNode body, String op, int expected) {
        requireListBody(body, op);
        if (body.getItems().size() != expected) {
            throw new BexException(op + " expects exactly " + expected + " operands");
        }
    }

    void requireObjectBody(FrozenNode body, String op, String... allowedFields) {
        requireObjectNode(body, op);
        Set<String> allowed = new LinkedHashSet<>();
        Collections.addAll(allowed, allowedFields);
        for (String field : authoredFieldNames(body)) {
            if (!allowed.contains(field)) {
                throw new BexException(op + " has unknown body field: " + field);
            }
        }
    }

    void requireObjectNode(FrozenNode body, String op) {
        if (body == null
                || body.getItems() != null
                || body.getValue() != null
                || body.getReferenceBlueId() != null
                || body.getPreviousBlueId() != null
                || body.getPosition() != null) {
            throw new BexException(op + " expects an object body");
        }
    }

    void requireProgramNode(FrozenNode node, String label) {
        if (node == null
                || node.getValue() != null
                || node.getItems() != null
                || node.getReferenceBlueId() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null) {
            throw new BexException("BEX " + label + " must be an object node");
        }
    }

    boolean hasNonListPayload(FrozenNode node) {
        return node.getValue() != null
                || node.getProperties() != null
                || hasLanguageFields(node)
                || node.getPreviousBlueId() != null
                || node.getPosition() != null;
    }

    boolean isScalarBody(FrozenNode body) {
        return isScalarNode(body);
    }

    /**
     * Blue preprocessing adds an exact core-type reference to an authored
     * scalar. That inferred metadata is part of the scalar representation, not
     * a BEX object literal field. Keep the exception deliberately narrow:
     * computed or additional Blue metadata must still compile as an object.
     */
    boolean isScalarNode(FrozenNode node) {
        if (node == null
                || node.getValue() == null
                || node.getItems() != null
                || node.getProperties() != null
                || node.getName() != null
                || node.getDescription() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getReferenceBlueId() != null
                || node.getBlue() != null
                || node.getContracts() != null
                || node.getSchema() != null
                || node.getMergePolicy() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null) {
            return false;
        }
        FrozenNode type = node.getType();
        if (type == null) {
            return true;
        }
        String expectedTypeBlueId = scalarTypeBlueId(node.getValue());
        return expectedTypeBlueId != null
                && type.isReferenceOnly()
                && expectedTypeBlueId.equals(type.getReferenceBlueId());
    }

    String scalarTypeBlueId(Object value) {
        if (value instanceof String) {
            return TEXT_TYPE_BLUE_ID;
        }
        if (value instanceof Boolean) {
            return BOOLEAN_TYPE_BLUE_ID;
        }
        if (value instanceof java.math.BigDecimal
                || value instanceof Float
                || value instanceof Double) {
            return DOUBLE_TYPE_BLUE_ID;
        }
        if (value instanceof java.math.BigInteger
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) {
            return INTEGER_TYPE_BLUE_ID;
        }
        return null;
    }

    CompiledExpression intrinsicExpr(FrozenNode body, CompileScope scope, String pointer) {
        if (body == null || body.getValue() != null || body.getItems() != null) {
            throw new BexException("$intrinsic expects an object body");
        }
        FrozenNode typeNode = required(prop(body, "type"), "$intrinsic.type");
        rejectBexAnywhereInStaticPattern(typeNode, pointer + "/type");
        String blueId = intrinsicTypeBlueId(typeNode);
        BexValue typeValue = BexValues.frozen(typeNode);
        if (blueId == null || blueId.isEmpty()) {
            throw new BexException("$intrinsic.type must resolve to a BlueId");
        }
        if (!intrinsics.supports(blueId)) {
            throw new BexException("Unsupported intrinsic BlueId: " + blueId);
        }
        requiredIntrinsicBlueIds.add(blueId);

        Map<String, CompiledExpression> fields = new LinkedHashMap<>();
        if (body.getProperties() != null) {
            for (String fieldName : BexUnicodeOrder.sortedCopy(body.getProperties().keySet())) {
                if ("type".equals(fieldName)) {
                    continue;
                }
                fields.put(fieldName, compileExpr(body.getProperties().get(fieldName), scope,
                        pointer + "/" + escape(fieldName)));
            }
        }
        return new IntrinsicExpr(blueId, typeValue, fields);
    }

    String intrinsicTypeBlueId(FrozenNode typeNode) {
        if (hasExplicitProperty(typeNode, "blueId")) {
            return text(explicitProp(typeNode, "blueId"));
        }
        if (typeNode.getReferenceBlueId() != null && !typeNode.getReferenceBlueId().isEmpty()) {
            return typeNode.getReferenceBlueId();
        }
        String blueId = BexNodeIdentity.safeBlueId(typeNode);
        if (blueId != null && !blueId.isEmpty()) {
            return blueId;
        }
        return text(typeNode);
    }

    CompiledExpression varExpr(FrozenNode body, CompileScope scope, String pointer) {
        if (body != null && body.getProperties() != null) {
            String name = requiredText(prop(body, "name"), "$var.name");
            return new VarExpr(scope.resolveSlot(name), pathOperandOrNull(body, scope, pointer));
        }
        return new VarExpr(scope.resolveSlot(requiredText(body, "$var")));
    }

    String constName(FrozenNode body) {
        if (body != null && body.getProperties() != null) {
            return requiredText(prop(body, "name"), "$const.name");
        }
        return requiredText(body, "$const");
    }

    PointerOperand pathOperandOrNull(FrozenNode body, CompileScope scope, String pointer) {
        if (body == null || body.getProperties() == null || prop(body, "path") == null) {
            return null;
        }
        return valuePointerOperand(prop(body, "path"), scope, pointer + "/path");
    }

    Set<String> kindSet(FrozenNode node) {
        Set<String> kinds = new LinkedHashSet<>();
        if (node.getItems() != null) {
            for (FrozenNode item : node.getItems()) {
                kinds.add(validateKind(requiredText(item, "$isKind.kind item")));
            }
        } else {
            kinds.add(validateKind(requiredText(node, "$isKind.kind")));
        }
        return Collections.unmodifiableSet(kinds);
    }

    String validateKind(String kind) {
        if ("undefined".equals(kind)
                || "null".equals(kind)
                || "text".equals(kind)
                || "integer".equals(kind)
                || "double".equals(kind)
                || "boolean".equals(kind)
                || "object".equals(kind)
                || "list".equals(kind)) {
            return kind;
        }
        throw new BexException("Unknown BEX kind: " + kind);
    }

    CompiledExpression collectionExpr(FrozenNode body, CompileScope scope, String pointer,
                                              CollectionOp op, String bodyField) {
        String operator = collectionOperatorName(op);
        CompiledExpression input = compileExpr(required(prop(body, "in"), operator + ".in"), scope, pointer + "/in");
        String itemName = requiredText(prop(body, "item"), operator + ".item");
        String keyName = prop(body, "key") != null ? requiredText(prop(body, "key"), operator + ".key") : null;
        String indexName = prop(body, "index") != null ? requiredText(prop(body, "index"), operator + ".index") : null;
        validateDistinctCollectionBindings(operator, itemName, keyName, indexName);
        CompileScope.Visibility visibility = scope.captureVisibility();
        try {
            int itemSlot = scope.declareOrGetSlot(itemName);
            int keySlot = keyName != null ? scope.declareOrGetSlot(keyName) : -1;
            int indexSlot = indexName != null ? scope.declareOrGetSlot(indexName) : -1;
            CompiledExpression expr = compileExpr(required(prop(body, bodyField), operator + "." + bodyField),
                    scope, pointer + "/" + bodyField);
            return new CollectionQueryExpr(input, itemSlot, keySlot, indexSlot, expr, op);
        } finally {
            scope.restoreVisibility(visibility);
        }
    }

    CompiledExpression reduceExpr(FrozenNode body, CompileScope scope, String pointer) {
        CompiledExpression input = compileExpr(required(prop(body, "in"), "$reduce.in"), scope, pointer + "/in");
        String accName = requiredText(prop(body, "acc"), "$reduce.acc");
        String itemName = requiredText(prop(body, "item"), "$reduce.item");
        String keyName = prop(body, "key") != null ? requiredText(prop(body, "key"), "$reduce.key") : null;
        String indexName = prop(body, "index") != null ? requiredText(prop(body, "index"), "$reduce.index") : null;
        validateDistinctCollectionBindings("$reduce", itemName, keyName, indexName);
        if (accName.equals(itemName) || accName.equals(keyName) || accName.equals(indexName)) {
            throw new BexException("$reduce.acc must use a different binding name");
        }
        CompiledExpression init = compileExpr(required(prop(body, "init"), "$reduce.init"), scope, pointer + "/init");
        CompileScope.Visibility visibility = scope.captureVisibility();
        try {
            int accSlot = scope.declareOrGetSlot(accName);
            int itemSlot = scope.declareOrGetSlot(itemName);
            int keySlot = keyName != null ? scope.declareOrGetSlot(keyName) : -1;
            int indexSlot = indexName != null ? scope.declareOrGetSlot(indexName) : -1;
            CompiledExpression expr = compileExpr(required(prop(body, "expr"), "$reduce.expr"), scope, pointer + "/expr");
            return new ReduceExpr(input, accSlot, init, itemSlot, keySlot, indexSlot, expr);
        } finally {
            scope.restoreVisibility(visibility);
        }
    }

    String collectionOperatorName(CollectionOp op) {
        switch (op) {
            case MAP:
                return "$map";
            case FILTER:
                return "$filter";
            case FLAT_MAP:
                return "$flatMap";
            case SOME:
                return "$some";
            case FIND:
                return "$find";
            case FIND_ENTRY:
                return "$findEntry";
            default:
                return "collection operator";
        }
    }

    void validateDistinctCollectionBindings(String operator, String itemName, String keyName, String indexName) {
        if (keyName != null && keyName.equals(itemName)) {
            throw new BexException(operator + ".key must use a different binding name than " + operator + ".item");
        }
        if (indexName != null && indexName.equals(itemName)) {
            throw new BexException(operator + ".index must use a different binding name than " + operator + ".item");
        }
        if (keyName != null && indexName != null && keyName.equals(indexName)) {
            throw new BexException(operator + ".key must use a different binding name than " + operator + ".index");
        }
    }

    CompiledExpression isExpr(FrozenNode body, CompileScope scope, String pointer) {
        if (body == null || body.getProperties() == null) {
            throw new BexException("$is expects an object body");
        }
        FrozenNode pattern = required(prop(body, "pattern"), "$is.pattern");
        rejectBexAnywhereInStaticPattern(pattern, pointer + "/pattern");
        return new IsExpr(
                compileExpr(required(prop(body, "node"), "$is.node"), scope, pointer + "/node"),
                pattern);
    }

    CompiledExpression documentExpr(FrozenNode body, CompileScope scope, String pointer) {
        boolean resolved = false;
        FrozenNode pointerNode = body;
        if (body != null && body.getProperties() != null && body.getProperties().containsKey("path")) {
            pointerNode = prop(body, "path");
            String view = text(prop(body, "view"));
            resolved = "resolved".equals(view);
        }
        return new DocumentExpr(pointerOperand(pointerNode, scope, pointer), resolved);
    }

    CompiledExpression contextPointerExpr(FrozenNode body, CompileScope scope, ContextKind kind, String pointer) {
        return new ContextPointerExpr(valuePointerOperand(body, scope, pointer), kind);
    }

    CompiledExpression bindingExpr(FrozenNode body, CompileScope scope, String pointer) {
        if (body != null && body.getValue() != null && body.getProperties() == null && body.getItems() == null) {
            String selector = String.valueOf(body.getValue());
            int slash = selector.indexOf('/');
            String name = slash >= 0 ? selector.substring(0, slash) : selector;
            if (name.isEmpty()) {
                throw new BexException("$binding short form requires a binding name");
            }
            String path = slash >= 0 ? selector.substring(slash) : "/";
            return new BindingExpr(new StaticTextExpr(name), StaticValuePointerOperand.of(path));
        }
        if (body == null || body.getProperties() == null) {
            throw new BexException("$binding expects a binding name or object form");
        }
        TextOperand name = textOrExpr(required(prop(body, "name"), "$binding.name"), scope, null, pointer + "/name");
        FrozenNode path = prop(body, "path") != null ? prop(body, "path") : scalarNode("/");
        return new BindingExpr(name, valuePointerOperand(path, scope, pointer + "/path"));
    }

    CompiledExpression stepsExpr(FrozenNode body, CompileScope scope, String pointer) {
        if (body.getValue() != null) {
            String selector = String.valueOf(body.getValue());
            int dot = selector.indexOf('.');
            String step = dot >= 0 ? selector.substring(0, dot) : selector;
            String path = dot >= 0 ? "/" + selector.substring(dot + 1) : "/";
            return new StepsExpr(new StaticTextExpr(step), StaticValuePointerOperand.of(path));
        }
        return new StepsExpr(textOrExpr(required(prop(body, "step"), "$steps.step"), scope, null, pointer + "/step"),
                valuePointerOperand(prop(body, "path") != null ? prop(body, "path") : scalarNode("/"), scope, pointer + "/path"));
    }

    CallExpr compileCall(FrozenNode body, CompileScope scope, String pointer) {
        String function = requiredText(prop(body, "function"), "$call.function");
        FunctionSignature signature = functionSignatures.get(function);
        if (signature == null) {
            throw new BexException("Unknown function: " + function);
        }
        List<CompiledExpression> argExpressions = new ArrayList<>();
        List<Integer> targetSlots = new ArrayList<>();
        Set<String> providedArgs = new LinkedHashSet<>();
        FrozenNode argsNode = prop(body, "args");
        if (argsNode != null) {
            validatePlainObjectContainer(argsNode, "$call.args");
            if (argsNode.getProperties() == null) {
                if (!argsNode.isEmptyNode()) {
                    throw new BexException("$call.args must be an object at " + pointer + "/args");
                }
            } else {
                for (String argName : BexUnicodeOrder.sortedCopy(argsNode.getProperties().keySet())) {
                    BexCompiledProgram.ArgSpec arg = signature.arg(argName);
                    if (arg == null) {
                        throw new BexException("Unknown argument " + argName + " for function " + function);
                    }
                    providedArgs.add(argName);
                    targetSlots.add(arg.slot());
                    argExpressions.add(compileExpr(argsNode.getProperties().get(argName), scope,
                            pointer + "/args/" + escape(argName)));
                }
            }
        }
        for (BexCompiledProgram.ArgSpec arg : signature.args()) {
            if (!providedArgs.contains(arg.name())) {
                throw new BexException("Missing argument " + arg.name() + " for function " + function);
            }
        }
        int[] slots = new int[targetSlots.size()];
        for (int i = 0; i < targetSlots.size(); i++) {
            slots[i] = targetSlots.get(i);
        }
        return new CallExpr(function,
                slots,
                argExpressions.toArray(new CompiledExpression[0]));
    }

    List<CompiledExpression> compileExprList(FrozenNode node, CompileScope scope, String pointer) {
        if (node == null || node.getItems() == null) {
            throw new BexException("Operator expects a list");
        }
        List<CompiledExpression> expressions = new ArrayList<>();
        for (int i = 0; i < node.getItems().size(); i++) {
            expressions.add(compileExpr(node.getItems().get(i), scope, pointer + "/" + i));
        }
        return expressions;
    }

    TextOperand textOrExpr(FrozenNode node, CompileScope scope, String defaultText, String pointer) {
        if (node == null) {
            return new StaticTextExpr(defaultText);
        }
        if (node.getValue() != null && node.getProperties() == null && node.getItems() == null) {
            return new StaticTextExpr(String.valueOf(node.getValue()));
        }
        return new DynamicTextExpr(compileExpr(node, scope, pointer), pointer);
    }

    PointerOperand pointerOperand(FrozenNode node, CompileScope scope, String pointer) {
        if (node != null && node.getProperties() != null && node.getProperties().containsKey("path")) {
            node = prop(node, "path");
        }
        if (node != null && node.getValue() != null && node.getProperties() == null && node.getItems() == null) {
            return StaticPointerOperand.of(String.valueOf(node.getValue()));
        }
        return new DynamicPointerOperand(compileExpr(node, scope, pointer));
    }

    PointerOperand valuePointerOperand(FrozenNode node, CompileScope scope, String pointer) {
        if (node != null && node.getProperties() != null && node.getProperties().containsKey("path")) {
            node = prop(node, "path");
        }
        if (node != null && node.getValue() != null && node.getProperties() == null && node.getItems() == null) {
            return StaticValuePointerOperand.of(String.valueOf(node.getValue()));
        }
        return new DynamicValuePointerOperand(compileExpr(node, scope, pointer));
    }

}
