package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestDecodeResult
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The provenance the artifact ships, read exactly as admission will read it.
 *
 * These rows guard the two ways a plugin dies at admission that its own unit tests would
 * never see: an api range that does not contain the host it was built against, and a manifest
 * whose declared steps disagree with what the contributor actually provides. Both are checks
 * on THIS artifact's real bytes, not fixtures.
 */
class AcpProvenanceTest {

    @Test
    fun `provenance is present in the built artifact's resources`() {
        // Fail-closed reading: required() throws rather than defaulting, so merely touching
        // these values proves the document exists and carries every key.
        assertNotNull(AcpPluginDeclaration.declaredVersion())
        val range = AcpPluginDeclaration.API_RANGE
        assertTrue(
            range.accepts(SemVer(0, 98, 0)),
            "the range built against the 0.98.0 SDK must admit the 0.98.0 host, got $range",
        )
    }

    @Test
    fun `the api range floor is the host floor the SDK requires and the ceiling is its next minor`() {
        val range = AcpPluginDeclaration.API_RANGE
        // The seam this plugin needs (registryStep) exists from 0.98.0; a floor below it would
        // claim compatibility with hosts that cannot run the plugin at all.
        assertEquals(SemVer(0, 98, 0), range.fromInclusive)
        assertTrue(range.accepts(SemVer(0, 98, 0)))
        assertTrue(!range.accepts(SemVer(1, 0, 0)), "the range must not outlive the minor it was measured in")
    }

    @Test
    fun `the manifest decodes and its declared steps equal the contributor's registrations`() {
        val manifest = AcpPluginDeclaration.manifest()
        val decoded = PluginManifestCodec.decode(PluginManifestCodec.encode(manifest))
        // decode() answers what ADMISSION would answer, not a manifest: a rejection here is the
        // artifact refusing to load, and the test must name that rather than NPE downstream.
        val roundTripped = (decoded as? PluginManifestDecodeResult.Accepted)?.manifest
            ?: throw AssertionError("admission would refuse this manifest: $decoded")
        assertEquals(manifest, roundTripped, "the manifest the build writes must read back identical")

        val declared = roundTripped.contributions.steps.map { it.stepKey.value }.sorted()
        val provided = AcpContributor().registrations().map { it.stepKey.value }.sorted()
        // This equality IS the cross-check admission performs; drifting here is a runtime refusal.
        assertEquals(declared, provided)
        assertEquals(listOf("pipelinek.acp.prompt"), declared)
    }

    @Test
    fun `registrations carry real provider metadata and not the legacy default`() {
        val registration = AcpContributor().registrations().single()
        // The default StepDefinitionContributor.registrations() stamps publisher
        // "legacy-external"; overriding it must actually replace it, or the audit projection
        // this override exists for silently never happens.
        assertEquals("pipelinek-acp", registration.provider.publisher)
        assertEquals(PluginStepId("pipelinek.acp.prompt"), registration.stepKey)
    }
}
