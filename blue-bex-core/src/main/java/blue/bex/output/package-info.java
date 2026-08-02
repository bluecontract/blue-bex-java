/**
 * Strict conversion and ordinary Blue identity establishment for values leaving
 * the BEX runtime.
 *
 * <p>Admission objects belong to one execution; successfully admitted metadata
 * is immutable and returns defensive mutable-Node copies where needed. Values,
 * kinds, and host boundaries are non-null unless explicitly defaulted. Invalid
 * runtime Blue content, missing identity evidence, or boundary failure fails the
 * run atomically. The output charge is admitted before validation; exact values
 * pass by identity, while transient values pay only for actual conversion and
 * direct identity work.</p>
 */
package blue.bex.output;
