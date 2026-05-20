package blue.bex;

import blue.bex.api.BexEngine;
import blue.bex.api.BexProgramSource;
import blue.bex.compile.BexCompiledProgram;
import blue.bex.compile.BexCompiledProgramKey;
import blue.bex.compile.BexNodeIdentity;
import blue.bex.compile.LruBexCompiledProgramCache;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import static blue.bex.test.BexTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class BexCompiledProgramCacheTest {
    @Test
    void differentNodesWithoutBlueIdDoNotCollide() {
        FrozenNode first = frozen(stepExpr(op("$document", "/status")));
        FrozenNode second = frozen(stepExpr(op("$document", "/count")));

        assertNotEquals(BexNodeIdentity.stable(first), BexNodeIdentity.stable(second));
        assertNotEquals(BexCompiledProgramKey.from(BexProgramSource.inline(first)),
                BexCompiledProgramKey.from(BexProgramSource.inline(second)));
    }

    @Test
    void sameNodeProducesSameIdentityAndCacheHit() {
        LruBexCompiledProgramCache cache = new LruBexCompiledProgramCache();
        BexEngine engine = BexEngine.builder().cache(cache).build();
        BexProgramSource source = BexProgramSource.inline(frozen(stepExpr(op("$document", "/status"))));

        BexCompiledProgram first = engine.compile(source);
        BexCompiledProgram second = engine.compile(source);

        assertSame(first, second);
        assertEquals(BexNodeIdentity.stable(source.programNode()), BexNodeIdentity.stable(source.programNode()));
    }

    @Test
    void entryNameParticipatesInCacheKey() {
        FrozenNode step = frozen(obj("type", "Blue/BEX Program"));
        FrozenNode definition = frozen(obj("functions", obj(
                "a", obj("expr", "A"),
                "b", obj("expr", "B")
        )));

        assertNotEquals(BexCompiledProgramKey.from(BexProgramSource.withDefinition(step, definition, "a")),
                BexCompiledProgramKey.from(BexProgramSource.withDefinition(step, definition, "b")));
    }
}
