package blue.bex.conformance;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Independent identity oracle for the empty-object BEX fixtures.
 *
 * <p>This test deliberately does not call the BEX output writer, the fixture
 * adapter, or Language's direct identity calculator.  It spells the canonical
 * JSON formulas and performs SHA-256/Base58 locally, keeping fixture goldens
 * independent of the conversion implementation under test.</p>
 */
class BexEmptyObjectIdentityOracleTest {
    private static final String BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final String BOOLEAN_TYPE =
            "AwvXD961fmnmqcSQhjMA7r15HpVh39cefb6ZTyUz2Fm2";

    @Test
    void listFixtureGoldensComeFromCanonicalJsonFormulas() {
        String emptyObject = hash("{}");
        String emptyList = hash("{\"$list\":\"empty\"}");
        String rawTrue = hash("true");
        String typedTrue = hash(
                "{\"type\":{\"blueId\":\"" + BOOLEAN_TYPE
                        + "\"},\"value\":true}");

        // A placeholder has an ordinary direct node identity when projected
        // as an item, while the list fold consumes its control-form identity.
        String placeholderDirect = hash(
                "{\"$empty\":{\"blueId\":\"" + typedTrue + "\"}}");
        String placeholderListContribution = hash(
                "{\"$empty\":{\"blueId\":\"" + rawTrue + "\"}}");

        String twoItems = append(
                append(emptyList, placeholderListContribution),
                emptyObject);
        String threeItems = append(twoItems, emptyList);

        assertEquals(
                "5ajuwjHoLj33yG5t5UFsJtUb3vnRaJQEMPqSLz6VyoHK",
                emptyObject);
        assertEquals(
                "4mfDwwrpfVGMwKn82vsr4rVX484P8DAMy5RNEVXqsy9h",
                emptyList);
        assertEquals(
                "6V647wzsPU3zXvrc5AwKsqbFmkgHWV8WAeyBfNG1T8Rx",
                placeholderDirect);
        assertEquals(
                "EWr9y2M2RDtpegE6C7i36F6vph8EKqbk5dWkCtmHLnBy",
                threeItems);
        assertEquals(
                "7oLSDVaBCSUqBgvH9FNTuoKD9spAB9cW1EBuWTLb87YR",
                twoItems);

        assertFixtureIdentities(
                "bex-empty-05",
                Arrays.asList(placeholderDirect, emptyObject, emptyList),
                threeItems);
        assertFixtureIdentities(
                "bex-empty-15",
                Arrays.asList(placeholderDirect, emptyObject),
                twoItems);
    }

    private static String append(String previous, String element) {
        return hash("{\"$listCons\":{\"elem\":{\"blueId\":\""
                + element + "\"},\"prev\":{\"blueId\":\""
                + previous + "\"}}}");
    }

    private static void assertFixtureIdentities(
            String fixtureId,
            List<String> expectedItems,
            String expectedRoot) {
        ConformancePackage.Fixture fixture = null;
        for (ConformancePackage.Fixture candidate
                : ConformancePackage.behaviorFixtures()) {
            if (fixtureId.equals(candidate.id())) {
                fixture = candidate;
                break;
            }
        }
        if (fixture == null) {
            fail("Missing fixture " + fixtureId);
        }

        List<String> actualItems = null;
        String actualRoot = null;
        for (Object raw : ConformancePackage.list(
                fixture.expected().get("assertions"),
                fixtureId + ".expected.assertions")) {
            Map<String, Object> assertion = ConformancePackage.map(
                    raw, fixtureId + ".assertion");
            if ("output.itemBlueIds".equals(assertion.get("actual"))) {
                actualItems = new ArrayList<String>();
                for (Object item : ConformancePackage.list(
                        assertion.get("expected"),
                        fixtureId + ".output.itemBlueIds")) {
                    actualItems.add(String.valueOf(item));
                }
            } else if ("output.nodeBlueId".equals(
                    assertion.get("actual"))) {
                actualRoot = String.valueOf(assertion.get("expected"));
            }
        }
        assertEquals(expectedItems, actualItems,
                fixtureId + " item identity goldens");
        assertEquals(expectedRoot, actualRoot,
                fixtureId + " root identity golden");
    }

    private static String hash(String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    canonicalJson.getBytes(StandardCharsets.UTF_8));
            return base58(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError("SHA-256 unavailable", ex);
        }
    }

    private static String base58(byte[] bytes) {
        int leadingZeroCount = 0;
        while (leadingZeroCount < bytes.length
                && bytes[leadingZeroCount] == 0) {
            leadingZeroCount++;
        }
        BigInteger value = new BigInteger(1, bytes);
        StringBuilder encoded = new StringBuilder();
        BigInteger radix = BigInteger.valueOf(58L);
        while (value.signum() > 0) {
            BigInteger[] quotientAndRemainder =
                    value.divideAndRemainder(radix);
            encoded.append(BASE58_ALPHABET.charAt(
                    quotientAndRemainder[1].intValue()));
            value = quotientAndRemainder[0];
        }
        for (int index = 0; index < leadingZeroCount; index++) {
            encoded.append('1');
        }
        return encoded.reverse().toString();
    }
}
