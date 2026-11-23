package pipelinek.acp

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.domain.step.Delivery
import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.ManifestSchemaVersion
import dev.rubentxu.pipeline.v2.domain.step.PipelineKApiRange
import dev.rubentxu.pipeline.v2.domain.step.PluginContributions
import dev.rubentxu.pipeline.v2.domain.step.PluginEventContribution
import dev.rubentxu.pipeline.v2.domain.step.PluginFamily
import dev.rubentxu.pipeline.v2.domain.step.PluginManifest
import dev.rubentxu.pipeline.v2.domain.step.PluginManifestCodec
import dev.rubentxu.pipeline.v2.domain.step.PluginReleaseRef
import dev.rubentxu.pipeline.v2.domain.step.PluginStepContribution
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor
import dev.rubentxu.pipeline.v2.domain.step.StepProviderMetadata
import dev.rubentxu.pipeline.v2.domain.step.StepRegistration
import dev.rubentxu.pipeline.v2.domain.step.TrustMetadata

/**
 * The external plugin's declaration, in one place: provenance read, manifest written,
 * registrations answered.
 *
 * ## One declaration, two readers
 * The manifest is emitted into the artifact at BUILD time (this file's `main`) and the same
 * object answers the RUNTIME's cross-check: the host compares what the manifest declares
 * against what the contributor actually provides, and a drift in either direction is a
 * refusal at admission. That comparison is only meaningful while both readers read THIS
 * object — a manifest typed by hand in the build script would be a second authority able to
 * describe a Step this code does not have.
 *
 * ## Fail-closed provenance
 * Every field below comes from the release-properties document the build writes. A missing
 * key throws instead of defaulting: a guessed api range admits a plugin against hosts nobody
 * measured, and the saga's stale literal proved where that ends — the plugin refusing the
 * very host that shipped it.
 */
object AcpPluginDeclaration {

    const val CONTRIBUTOR_ID: String = "pipelinek.acp"
    const val RELEASE_PROPERTIES_RESOURCE: String = "META-INF/pipelinek-acp-release.properties"

    private const val PUBLISHER_KEY = "pipeline.acp.publisher"
    private const val NAMESPACE_KEY = "pipeline.acp.namespace"
    private const val VERSION_KEY = "pipeline.acp.release.version"
    private const val DIGEST_KEY = "pipeline.acp.release.digest"
    private const val MIN_KEY = "pipeline.acp.compatibility.min"
    private const val UNTIL_KEY = "pipeline.acp.compatibility.untilExclusive"
    private const val SDK_VERSION_KEY = "pipeline.acp.sdk.version"

    /** The step families this plugin contributes. The manifest and the contributor read THIS. */
    val STEPS: List<StepDefinition<*, *>> =
        listOf(
            AcpPromptStepDefinition,
            AcpTaskStepDefinition,
            AcpSessionStepDefinition,
            AcpReactiveStepDefinition,
            AcpReactionStepDefinition,
        )

    private val props: Map<String, String> by lazy { releaseProperties(AcpPluginDeclaration::class.java) }

    private fun required(key: String): String =
        System.getProperty(key) ?: props[key] ?: error(
            "Missing provenance key '$key' (no system property, no $RELEASE_PROPERTIES_RESOURCE). " +
                "The plugin refuses to declare what the build did not measure.",
        )

    /** The version this artifact declares — the value the build stamped into provenance. */
    fun declaredVersion(): String = required(VERSION_KEY)

    /**
     * The SDK version this artifact was compiled against, as the build stamped it.
     *
     * The api range is DERIVED from that version and from the host that will admit the
     * artifact, so the SDK version is the fact a reader needs to check the range against —
     * and it is read from the same provenance document, never written by hand.
     */
    fun declaredSdkVersion(): String = required(SDK_VERSION_KEY)

    /** Compatibility range, DERIVED at build time from the resolved SDK and the measured host. */
    val API_RANGE: PipelineKApiRange by lazy {
        PipelineKApiRange(parseSemVer(required(MIN_KEY)), parseSemVer(required(UNTIL_KEY)))
    }

    /** Fail-closed provenance: never accept a hand-typed digest. */
    fun provider(classLoader: ClassLoader = AcpPluginDeclaration::class.java.classLoader): StepProviderMetadata {
        val plugin: ResourceRef = ResourceRefs.plugin(required(NAMESPACE_KEY), "acp")
        return StepProviderMetadata.create(
            plugin = plugin,
            release = PluginReleaseRef(plugin, parseSemVer(declaredVersion()), Digest(required(DIGEST_KEY))),
            publisher = required(PUBLISHER_KEY),
            families = setOf(PluginFamily.UTILITIES),
            delivery = Delivery.EXTERNAL_REFERENCE,
            trust = TrustMetadata.Unverified(),
        )
    }

    /** The manifest document. Its step list and [STEPS] are the same fact, on purpose. */
    fun manifest(classLoader: ClassLoader = AcpPluginDeclaration::class.java.classLoader): PluginManifest {
        val provider = provider(classLoader)
        return PluginManifest(
            schemaVersion = ManifestSchemaVersion.CURRENT,
            plugin = provider.plugin,
            release = provider.release,
            apiRange = API_RANGE,
            publisher = provider.publisher,
            families = provider.families,
            delivery = provider.delivery,
            trust = provider.trust,
            contributions = PluginContributions(
                // In the codec's canonical order (by step key): the manifest codec writes the
                // steps sorted that way and does not reorder them on the way back, so a
                // declaration in any other order would make the document the build writes
                // differ from the manifest this plugin declares — and the round trip that
                // proves the two agree would fail.
                steps = STEPS.sortedBy { it.contract.key.value }.map {
                    PluginStepContribution(it.contract.key, it.contract.requiredCapabilities)
                },
                directives = emptyList(),
                // The kinds the contributor actually provides, read from the same matrix that
                // names the facts: the host cross-checks this list against what the
                // EventDefinitionContributor registers, and a manifest that declared a kind by
                // hand would be a second authority able to describe a fact nobody publishes.
                events = AcpSchemaMatrix.kinds.sorted().map { PluginEventContribution(it) },
                capabilities = STEPS.flatMapTo(linkedSetOf()) { it.contract.requiredCapabilities },
            ),
        )
    }

    private fun parseSemVer(raw: String): SemVer {
        val parts = raw.split("-")[0].split(".")
        require(parts.size == 3) { "SemVer must have 3 numeric components (got '$raw')" }
        return SemVer(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
    }

    internal fun releaseProperties(ownClass: Class<*>): Map<String, String> {
        // Pinned to the JAR the JVM loaded THIS class from, not to classpath order: a forged
        // sibling JAR carrying the same resource name earlier on the classpath would otherwise
        // override this plugin's real provenance.
        val pluginJar = ownJarUrl(ownClass)
            ?: return readFromClassLoader(ownClass.classLoader)
        return readFromJar(pluginJar, RELEASE_PROPERTIES_RESOURCE)
            ?: readFromClassLoader(ownClass.classLoader)
    }

    private fun readFromClassLoader(classLoader: ClassLoader): Map<String, String> {
        val resource = classLoader.getResource(RELEASE_PROPERTIES_RESOURCE) ?: return emptyMap()
        val text = resource.openStream().use { it.readBytes().toString(Charsets.UTF_8) }
        return parseProperties(text)
    }

    private fun readFromJar(jarUrl: java.net.URL, resourcePath: String): Map<String, String>? {
        // Open the JAR by file path, not by JarURLConnection: a `file:` URL pointing at a
        // `.jar` is NOT a JarURLConnection, the cast throws, a swallow would fall back to the
        // system classloader — the exact lookup a forged sibling exploits.
        return try {
            val path = java.nio.file.Paths.get(jarUrl.toURI())
            val text = java.util.jar.JarFile(path.toFile()).use { jar ->
                jar.getJarEntry(resourcePath)?.let { entry ->
                    jar.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                }
            } ?: return null
            parseProperties(text)
        } catch (_: java.io.IOException) {
            null
        } catch (_: java.net.URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseProperties(text: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf('=')
            if (idx > 0) map[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
        }
        return map
    }

    private fun ownJarUrl(klass: Class<*>): java.net.URL? {
        val codeSource = klass.protectionDomain?.codeSource ?: return null
        val url = codeSource.location ?: return null
        return if (url.protocol == "jar" || url.path.endsWith(".jar")) url else null
    }
}

/**
 * ServiceLoader entry point, discovered through
 * `META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor`.
 *
 * [registrations] is overridden, not inherited: the default wraps every definition in
 * legacy-shape metadata, and a new contributor shipping legacy provenance would emit event
 * envelopes with no provider projection — the audit trail this plugin's identity exists to
 * carry. The real metadata comes from the same declaration the manifest is built from.
 */
class AcpContributor : StepDefinitionContributor {
    override val id: String = AcpPluginDeclaration.CONTRIBUTOR_ID

    override fun definitions(): Iterable<StepDefinition<*, *>> = AcpPluginDeclaration.STEPS

    override fun registrations(): Iterable<StepRegistration<*, *>> {
        val provider = AcpPluginDeclaration.provider()
        return AcpPluginDeclaration.STEPS.map { StepRegistration(it, provider) }
    }
}

/**
 * Build-time entry point: emit the manifest document to stdout.
 *
 * A missing provenance key makes this throw and the build fail, instead of shipping a JAR
 * without `META-INF/pipelinek/plugin-manifest.json` — a JAR admission would refuse, and a
 * silent skip here would produce an artifact that passes its own build and fails its own
 * admission.
 */
fun main() {
    print(PluginManifestCodec.encode(AcpPluginDeclaration.manifest()))
}
