package pipelinek.acp

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The matrix measured against the SDK it was compiled with.
 *
 * The point of these rows is not that the matrix is big: it is that the matrix cannot quietly
 * stop being true. An SDK uptick adds a variant, and a plugin that never noticed answers the
 * new traffic with the old vocabulary — which reads as an agent that went silent. Reflecting
 * over the sealed subclasses of the shipped jar is what turns that drift into a red test.
 */
class AcpSchemaMatrixTest {

    /** The SDK's own variant names, read from the classes on the test classpath. */
    private fun sealedSubclasses(parent: Class<*>): Set<String> =
        parent.declaredClasses.mapNotNull { it.simpleName.takeIf { name -> name.isNotEmpty() } }
            .filterNot { it == "Companion" }
            .toSet()

    @Test
    fun `every session-update variant the SDK ships has a row`() {
        val sdkVariants = sealedSubclasses(SessionUpdate::class.java)
        val inventoried = AcpSchemaMatrix.SESSION_UPDATES.map { it.variant }.toSet()

        assertEquals(
            sdkVariants,
            inventoried,
            "the matrix and the sealed SessionUpdate variants disagree: the SDK added or removed " +
                "a variant, and the plugin's vocabulary must be widened (or trimmed) on purpose, " +
                "never by accident",
        ).also { assertTrue(sdkVariants.isNotEmpty(), "reflection found no variants at all") }
    }

    @Test
    fun `every content block the SDK ships has a redaction row`() {
        val sdkVariants = sealedSubclasses(ContentBlock::class.java)
        val inventoried = AcpSchemaMatrix.CONTENT_BLOCKS.map { it.variant }.toSet()

        assertEquals(
            sdkVariants,
            inventoried,
            "the content-block whitelist no longer covers the SDK's blocks: a block with no row " +
                "is a block whose fields nobody decided to read",
        )
    }

    @Test
    fun `no variant may trigger a mutation operation`() {
        // AC#1's second clause, asserted over the whole inventory rather than over a sample:
        // the flag is false for every row today, and a row that flipped it would have to be
        // argued into the matrix on purpose.
        val rows = AcpSchemaMatrix.SESSION_UPDATES + AcpSchemaMatrix.LIFECYCLE
        rows.forEach { row ->
            assertFalse(row.mutating, "row '${row.variant}' declares itself able to mutate")
        }
    }

    @Test
    fun `a chunk answers with telemetry and never with a mutation`() {
        val chunk = AcpSchemaMatrix.rowOf("AgentMessageChunk")
        assertNotNull(chunk, "the chunk variant is not inventoried")
        assertEquals(AcpLane.TELEMETRY, chunk!!.lane)
        assertFalse(chunk.mutating)
    }

    @Test
    fun `the vocabulary names one kind per fact and the unknown variant is explicit`() {
        val kinds = AcpSchemaMatrix.kinds
        assertEquals(kinds.size, kinds.toSet().size, "two facts share a kind")
        kinds.forEach { assertTrue(it.startsWith(AcpFactToken.KIND_PREFIX), "kind '$it' escapes the namespace") }
        assertTrue(AcpFactToken.UNKNOWN_UPDATE in AcpSchemaMatrix.tokens, "no explicit unknown fact")
        assertNotNull(AcpSchemaMatrix.rowOf("UnknownSessionUpdate"))
    }

    @Test
    fun `a variant nobody inventoried has no row, and the lane it falls to is control`() {
        // The two halves of the drift answer: the matrix admits it does not know the variant,
        // and the fact that carries it travels in the lane that is never dropped.
        assertNull(AcpSchemaMatrix.rowOf("VariantNobodyInvented"))
        assertEquals(AcpLane.CONTROL, AcpSchemaMatrix.laneOf("a-token-not-in-the-matrix"))
        assertEquals(AcpLane.CONTROL, AcpSchemaMatrix.laneOf(AcpFactToken.EXCHANGE_STARTED))
        assertEquals(AcpLane.REACTIVE, AcpSchemaMatrix.laneOf(AcpFactToken.TOOL_CALL))
        assertEquals(AcpLane.TELEMETRY, AcpSchemaMatrix.laneOf(AcpFactToken.AGENT_MESSAGE_CHUNK))
    }

    @Test
    fun `no content block row reads the bytes of a payload`() {
        // The redaction rule as data: a row may name metadata and a length, never the content
        // itself — that is what keeps an image out of the durable log.
        AcpSchemaMatrix.CONTENT_BLOCKS.forEach { row ->
            row.readable.forEach { field ->
                assertFalse(
                    field in FORBIDDEN_FIELDS,
                    "content row '${row.variant}' reads '$field', which carries the payload",
                )
            }
        }
    }

    private companion object {
        /** The fields that ARE a payload, or the envelope anything can hide in. */
        val FORBIDDEN_FIELDS: Set<String> =
            setOf("data", "blob", "rawInput", "rawOutput", "rawJson", "_meta")
    }
}
