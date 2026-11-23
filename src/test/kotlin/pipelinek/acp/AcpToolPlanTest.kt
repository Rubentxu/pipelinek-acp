package pipelinek.acp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PKH-139 AC#1/AC#3 as DATA laws: every exposed tool compiles to a static slot address the
 * engine can resolve, every bound is bounded, and anything the gateway could not honour is
 * refused at compile time — where the refusal can still name every problem at once, before
 * the agent ever sees a `tools/list`.
 */
class AcpToolPlanTest {

    private fun compiled(vararg tools: AcpExposedToolDeclaration, taken: Set<String> = emptySet()): List<AcpCompiledTool> {
        val plan = compileAcpToolPlan(tools.toList(), taken)
        assertTrue(plan is AcpToolPlanCompilation.Compiled, "the plan must compile: ${(plan as? AcpToolPlanCompilation.Rejected)?.reasons}")
        return (plan as AcpToolPlanCompilation.Compiled).tools
    }

    private fun rejected(reasonsMustContain: List<String>, vararg tools: AcpExposedToolDeclaration, taken: Set<String> = emptySet()) {
        val plan = compileAcpToolPlan(tools.toList(), taken)
        assertTrue(plan is AcpToolPlanCompilation.Rejected, "the plan must be refused, got $plan")
        val reasons = (plan as AcpToolPlanCompilation.Rejected).reasons.joinToString("; ")
        reasonsMustContain.forEach {
            assertTrue(it in reasons, "refusal '$it' missing from: $reasons")
        }
    }

    @Test
    fun `an exposed tool compiles to the static slot address the gateway will dispatch`() {
        val tool = compiled(
            acpExpose(
                "runTests",
                "run the project test suite",
                AcpToolSchema(listOf(acpToolArgument("filter", "string", required = true))),
            ),
        ).single()

        assertEquals("runTests", tool.id)
        assertEquals(tool.slot.value, tool.id, "the slot IS the declared address, as with reactions")
        assertEquals(
            "acp-tool.runTests",
            tool.attemptKey.value,
            "tool dispatches derive their durable identity from their own name, apart from reaction keys",
        )
    }

    @Test
    fun `an empty tool list compiles to no gateway at all`() {
        // No declarations must not open a port: the shipped turn keeps its shape, and a gateway
        // nobody asked for is exactly the generic access AC#5 refuses.
        val plan = compileAcpToolPlan(emptyList())
        assertTrue(plan is AcpToolPlanCompilation.Compiled)
        assertEquals(emptyList<AcpCompiledTool>(), (plan as AcpToolPlanCompilation.Compiled).tools)
    }

    @Test
    fun `a blank tool id addresses no child and is refused`() {
        rejected(listOf("blank id"), acpExpose("", "names nothing"))
    }

    @Test
    fun `two tools claiming one slot address are refused together`() {
        val plan = compileAcpToolPlan(
            listOf(acpExpose("dup", "first"), acpExpose("dup", "second")),
        )
        val reasons = (plan as AcpToolPlanCompilation.Rejected).reasons.joinToString("; ")
        assertTrue("same slot address" in reasons, "the refusal says why: $reasons")
    }

    @Test
    fun `a tool claiming a reaction slot address is refused before launch`() {
        // One body child, one dispatcher: a name that routes BOTH an event reaction and a tool
        // call would make the journal attribute an invocation to the wrong half of the turn.
        rejected(
            listOf("already claimed by a reaction"),
            acpExpose("alert", "a tool stealing a reaction's name"),
            taken = setOf("alert"),
        )
    }

    @Test
    fun `an argument type outside the closed vocabulary is refused not guessed`() {
        // The gateway validates what it advertises; an object/array type it does not implement
        // would be advertised unchecked.
        rejected(
            listOf("the gateway speaks"),
            acpExpose(
                "echo",
                "echoes",
                AcpToolSchema(listOf(acpToolArgument("payload", "object"))),
            ),
        )
    }

    @Test
    fun `a blank or duplicated argument name is refused`() {
        rejected(
            listOf("blank name"),
            acpExpose("echo", "echoes", AcpToolSchema(listOf(acpToolArgument("", "string")))),
        )
        rejected(
            listOf("declared twice"),
            acpExpose(
                "echo",
                "echoes",
                AcpToolSchema(listOf(acpToolArgument("x", "string"), acpToolArgument("x", "number"))),
            ),
        )
    }

    @Test
    fun `a policy limit outside its bounds is refused`() {
        rejected(
            listOf("admits no call"),
            acpExpose("idle", "never dispatchable", policy = AcpToolPolicy(maxConcurrent = 0)),
        )
        rejected(
            listOf("is a dead advertisement"),
            acpExpose("dead", "never callable", policy = AcpToolPolicy(maxCalls = 0)),
        )
        rejected(
            listOf("exceeds the cap"),
            acpExpose("flood", "unbounded", policy = AcpToolPolicy(maxCalls = 100_000)),
        )
    }
}
