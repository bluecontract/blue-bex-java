# Compiler and immutable IR

The BEX compiler turns one selected immutable source tree into an immutable
compiled program. Compilation is deterministic, side-effect free, and separate
from execution.

## Compilation pipeline

Conceptually the compiler performs these stages:

1. classify the root as a full program or expression;
2. validate declarations, reserved names, static fields, and operator shapes;
3. collect constants, functions, argument patterns, and intrinsic requirements;
4. validate function calls and reject recursive call graphs;
5. compile expressions/statements into immutable typed instruction objects;
6. retain source paths and operator/function metadata for diagnostics and gas;
7. produce one immutable `BexCompiledProgram` plus its exact cache key inputs.

The operator catalog is the single recognition/metadata inventory for all 86
portable operators. It records operator kind/family and supports compiler,
coverage, diagnostics, and documentation consistency. Portable operators are
not dynamically registered; `$intrinsic` remains the only host extension point.

## Static and dynamic operands

Each operator defines which fields are static syntax and which are expressions.
Static names, pointers, patterns, and control fields are validated once. Dynamic
operands remain compiled expressions and are evaluated only when their operator
semantics require them.

This distinction prevents accidental execution inside Blue type/pattern fields
and preserves lazy semantics. A compiler refactor must never replace a lazy
operand with an eager Java evaluation.

## Immutable compiled form

Compiled programs, function definitions, instruction objects, declared patterns,
and required-intrinsic sets are immutable. Executable IR constructors and
instruction types are implementation-owned; consumers receive an opaque
`BexCompiledProgram` handle and cannot inject arbitrary mutable executable
nodes. A compiled object must not retain a
mutable source `Node`, execution context, frame, accumulator, or host session.
Run-local state lives only in runtime objects.

Instruction objects expose behavior through narrow runtime interfaces. Internal
IR classes are not a portable serialization format or public source syntax; the
selected Blue program remains the identity-bearing source.

## Cache identity

Compilation caching is keyed by the selected source identity/fingerprint plus
every configuration element that can change compiled meaning, including compiler
identity, BEX registry identity, gas schedule identity/weights, relevant Blue
Language registry identity, and intrinsic registry identity.

The same exact environment identity is retained in the compiled handle and
checked before execution. A program compiled with another intrinsic registry,
even one supporting the same BlueIds, is rejected rather than run under a
different implementation or gas catalog.

A hit must be observationally identical to a cold compile. Cache state cannot
change BEX gas, results, failure ordering, or provider behavior. Cache entries do
not capture bindings or other execution state.

## Compile-time failures

Compilation fails closed for unknown operators, invalid operator shape, illegal
name containers, duplicate/unknown declarations, missing/extra function
arguments, unknown constants/functions, recursive calls, invalid static
pointers/patterns, unsupported intrinsic BlueIds, and other statically knowable
defects.

Diagnostics retain the closest source path and operator/function context. The
compiler does not turn invalid source into runtime `undefined`.

## Evolving the compiler

Large operator families should remain cohesive and acyclic; shared parsing,
operand, pointer, and diagnostic utilities belong below family compilers rather
than importing the runtime back into compilation. A new portable operator must
update the specification, catalog, immutable IR/compiler, runtime semantics,
named gas, fixtures, coverage, docs, and API review together. See
[Adding an operator](adding-an-operator.md).
