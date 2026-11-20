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

    /** The SDK version the build stamped, read from the provenance document itself. */
    private fun declaredSdk(): SemVer {
        val raw = AcpPluginDeclaration.declaredSdkVersion()
        val parts = raw.split("-")[0].split(".").map { it.toInt() }
        require(parts.size == 3) { "the stamped SDK version is not a numeric triple: '$raw'" }
        return SemVer(parts[0], parts[1], parts[2])
    }

    @Test
    fun `provenance is present in the built artifact's resources`() {
        // Fail-closed reading: required() throws rather than defaulting, so merely touching
        // these values proves the document exists and carries every key.
        assertNotNull(AcpPluginDeclaration.declaredVersion())
        val range = AcpPluginDeclaration.API_RANGE
        val sdk = declaredSdk()
        assertTrue(
            range.accepts(sdk),
            "the range built against SDK $sdk must admit that SDK's host, got $range",
        )
    }

    @Test
    fun `the api range admits the SDK it was built against and ends at its next minor`() {
        val range = AcpPluginDeclaration.API_RANGE
        val sdk = declaredSdk()
        // The floor is derived (the newer of the compiled SDK and the measured host) and the
        // ceiling is the next minor of the SDK — so the range must admit the SDK itself and
        // end where that minor ends. Neither number is written here: a version typed into
        // this test would be a second answer, and a stale one.
        assertTrue(range.accepts(sdk), "the range must admit the SDK it was built against: $range")
        assertEquals(
            SemVer(sdk.major, sdk.minor + 1, 0),
            range.untilExclusive,
            "the ceiling is the next minor of the SDK",
        )
        assertTrue(
            !range.accepts(SemVer(sdk.major, sdk.minor + 1, 0)),
            "the range must not outlive the minor it was measured in",
        )
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
        assertEquals(
            listOf(
                "pipelinek.acp.prompt",
                "pipelinek.acp.reaction",
                "pipelinek.acp.reactive",
                "pipelinek.acp.session",
                "pipelinek.acp.task",
            ),
            declared,
        )
    }

    @Test
    fun `registrations carry real provider metadata and not the legacy default`() {
        val registration = AcpContributor().registrations().first()
        // The default StepDefinitionContributor.registrations() stamps publisher
        // "legacy-external"; overriding it must actually replace it, or the audit projection
        // this override exists for silently never happens.
        assertEquals("pipelinek-acp", registration.provider.publisher)
        assertEquals(PluginStepId("pipelinek.acp.prompt"), registration.stepKey)
    }
}
