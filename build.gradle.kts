import java.io.ByteArrayOutputStream
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

group = "pipelinek.acp"
version = "0.1.0"

// INDEPENDENT BUILD. This repository never depends on PipelineK's source tree — the whole
// premise of the ACP front (validation task PKH-144) is that the plugin is implementable
// against the PUBLISHED SDK with zero core modifications.
//
//   -PsdkRepo=<dir>      maven repository holding the published SDK artifacts
//   -PsdkVersion=<ver>   the SDK version to resolve
//   -PpipelinekHostVersion=<ver>  the host the gate measured with `pipelinek version`
//
// The repository is the release asset `pipelinek-<version>.sdk.zip` unpacked; point
// `-PsdkRepo` at the directory it unpacks into. There is no Maven Central publication of
// the contracts, so the release pins the version. No defaults, on purpose: a default could
// only be one that fails to resolve hundreds of lines below the line that matters.
val sdkRepo: String = requireNotNull(providers.gradleProperty("sdkRepo").orNull) {
    "-PsdkRepo is required: point it at the directory the SDK bundle unpacks into."
}
val sdkVersion: String = requireNotNull(providers.gradleProperty("sdkVersion").orNull) {
    "-PsdkVersion is required: e.g. -PsdkVersion=0.98.0"
}
val hostVersionRaw: String? = providers.gradleProperty("pipelinekHostVersion").orNull

val acpVersion = "0.34.0"
// The host ships kotlinx-serialization 1.11.0 and coroutines 1.11.0; pinning the SAME
// versions means the classes the plugin was compiled against are the classes the host's
// parent-first classloader will hand it, and the fat JAR never carries a second copy of
// something the installation already owns.
val hostSerializationVersion = "1.11.0"
val hostCoroutinesVersion = "1.11.0"

repositories {
    mavenCentral()
    maven {
        name = "sdk"
        url = uri(sdkRepo)
    }
}

configurations {
    // The SDK contracts are `compileOnly` (the host supplies them at runtime), so they are
    // absent from `runtimeClasspath`. The manifest emitter needs them at execution time;
    // this configuration is the explicit, reviewable list of what it runs against.
    create("sdkForManifestEmission")
}

dependencies {
    // `compileOnly`: the plugin ships its own classes and the HOST supplies the contracts.
    // A plugin that bundled the contracts would run beside a second copy of them — the
    // two-authorities split the published seam exists to prevent.
    compileOnly("dev.rubentxu.pipelinek:pipelinek-domain:$sdkVersion")
    compileOnly("dev.rubentxu.pipelinek:pipelinek-scripting-api:$sdkVersion")

    // The ACP client is private machinery of this plugin: nothing in a pipeline script
    // touches it, only the step handler does. It therefore belongs on the runtime
    // classpath, packed into the operator artifact (a `--plugin-jar` entry carries files,
    // not coordinates, so transitive resolution is this build's job, not the host's).
    implementation("com.agentclientprotocol:acp:$acpVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$hostCoroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$hostSerializationVersion")
    implementation("org.slf4j:slf4j-nop:2.0.16") // kotlin-logging needs A slf4j backend; NOP keeps the agent's stdout clean of log chatter.

    add("sdkForManifestEmission", "dev.rubentxu.pipelinek:pipelinek-domain:$sdkVersion")

    // The contract tests CHECK the engine instead of only compiling against it; a test is
    // not shipped, so putting the contracts on the test classpath costs nothing.
    testImplementation("dev.rubentxu.pipelinek:pipelinek-domain:$sdkVersion")
    testImplementation("dev.rubentxu.pipelinek:pipelinek-scripting-api:$sdkVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$hostCoroutinesVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // The provenance tests read what the build writes; without this edge the manifest emitter
    // is only a FINALIZER of processResources, and a finalizer runs after its task's
    // dependents — i.e. after the test that reads it.
    dependsOn(computeAcpRelease)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = providers.gradleProperty("showTestStdout").isPresent
    }
    // The provenance the manifest emitter writes lands in build/resources/main, which IS
    // on the test runtime classpath (sourceSets output), so contract tests read the same
    // release-properties document the shipped artifact carries.
}

tasks.jar {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// ---------------------------------------------------------------------------------------
// Provenance: release properties with a DERIVED api range, fail-closed.
// ---------------------------------------------------------------------------------------
//
// The saga example taught the lesson this task re-applies: an api range typed by hand went
// stale once and the plugin refused the very host that shipped it. So the range comes from
// the resolved SDK version and the measured host version — and a build that cannot name
// the host refuses to invent one.
//
// The declared digest is the digest of the plugin's PAYLOAD (classes + resources minus the
// documents that carry or derive from the digest itself), the saga convention. The runtime
// measures the bytes of the FILE that carries it; those are two different magnitudes and
// are never compared against each other — `PreResolvedComposition` says exactly that.

val acpReleaseProps =
    layout.buildDirectory.file("resources/main/META-INF/pipelinek-acp-release.properties")

val acpPublisher = providers.gradleProperty("pipeline.acp.publisher").orElse("pipelinek-acp")
val acpNamespace = providers.gradleProperty("pipeline.acp.namespace").orElse("pipelinek-acp-plugin")

val computeAcpRelease = tasks.register("computeAcpRelease") {
    group = "acp"
    description = "write this plugin's release provenance (version, payload digest, derived api range)."

    inputs.property("sdkVersion", sdkVersion)
    inputs.property("pluginVersion", version.toString())
    hostVersionRaw?.let { inputs.property("pipelinekHostVersion", it) }
    dependsOn("compileKotlin", "processResources")
    outputs.file(acpReleaseProps)

    doLast {
        val host = requireNotNull(hostVersionRaw) {
            "-PpipelinekHostVersion is required: the api range's floor is derived from the " +
                "host that will admit this plugin, and a build that names no host would be " +
                "inventing one. The gate passes it from `pipelinek version`."
        }
        require(host.matches(Regex("""\d+\.\d+\.\d+"""))) {
            "pipelinekHostVersion must be a bare numeric triple (got '$host')."
        }
        val hostParts = host.split(".").map { it.toInt() }
        val sdkParts = sdkVersion.split("-")[0].split(".").map { it.toInt() }
        // The floor is the NEWER of the compiled SDK and the measured host. A plugin
        // compiled against 0.98.0 cannot claim to run on an older host whose DSL lacks
        // `StepEmitter.registryStep` (the seam appeared in 0.98.0), and a floor above the
        // host would be refused by admission — the floor IS the measured fact.
        val byVersion = compareBy<List<Int>>({ it[0] }).thenBy { it[1] }.thenBy { it[2] }
        val floor = listOf(hostParts, sdkParts).maxWithOrNull(byVersion)!!
        // The ceiling is the NEXT MINOR of the resolved SDK: `accepts()` is
        // `runtime < untilExclusive`, so a ceiling equal to sdkVersion would make the
        // plugin refuse the very host it was built against.
        val ceiling = listOf(sdkParts[0], sdkParts[1] + 1, 0)

        val classesDir = layout.buildDirectory.dir("classes/kotlin/main").get().asFile
        val resourcesDir = layout.buildDirectory.dir("resources/main").get().asFile
        // The digest MUST exclude the provenance file (it carries the digest — a
        // fixed-point equation with no solution) and the emitted manifest (whose value
        // contains the digest; including it made per-worktree digests shift in lockstep,
        // proven by the saga and directive reproducibility tests).
        val manifestPath = layout.buildDirectory
            .file("resources/main/META-INF/pipelinek/plugin-manifest.json")
            .get().asFile.absolutePath
        val excluded = setOf(acpReleaseProps.get().asFile.absolutePath, manifestPath)

        val files = listOf(classesDir, resourcesDir)
            .filter { it.exists() }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.absolutePath !in excluded }
                    .map { it.absolutePath }
                    .toList()
            }
            .sorted()

        require(files.isNotEmpty()) {
            "no class or resource files to hash; refusing to emit provenance for an empty artifact"
        }

        // Hash each file's NAME with its bytes, in sorted order. The name matters as much
        // as the content: two artifacts that swap a class for a resource of the same
        // length would otherwise hash identically.
        val digest = MessageDigest.getInstance("SHA-256")
        for (path in files) {
            val file = file(path)
            digest.update(file.name.toByteArray(Charsets.UTF_8))
            digest.update(file.readBytes())
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }

        acpReleaseProps.get().asFile.also { it.parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("pipeline.acp.publisher=${acpPublisher.get()}")
                appendLine("pipeline.acp.namespace=${acpNamespace.get()}")
                appendLine("pipeline.acp.release.version=$version")
                appendLine("pipeline.acp.release.digest=sha256:$hex")
                appendLine("pipeline.acp.compatibility.min=${floor.joinToString(".")}")
                appendLine("pipeline.acp.compatibility.untilExclusive=${ceiling.joinToString(".")}")
                appendLine("pipeline.acp.sdk.version=$sdkVersion")
            },
        )
        println("acp: provenance written (payload digest=sha256:${hex.take(16)}...)")
        println(
            "acp: apiRange=[${floor.joinToString(".")}, ${ceiling.joinToString(".")}) " +
                "derived from sdkVersion=$sdkVersion host=$host",
        )
    }
}

val acpManifest = layout.buildDirectory.file("resources/main/META-INF/pipelinek/plugin-manifest.json")

val emitAcpManifest = tasks.register<JavaExec>("emitAcpManifest") {
    group = "acp"
    description = "emit the machine-readable plugin manifest into the artifact."

    dependsOn(computeAcpRelease)
    mainClass.set("pipelinek.acp.AcpPluginDeclarationKt")

    // The main() reads its release properties off ITS OWN classpath, so the freshly
    // generated resources directory has to be on it — and so do the SDK contracts, which
    // are compileOnly and therefore absent from runtimeClasspath.
    classpath = files(
        sourceSets["main"].runtimeClasspath,
        layout.buildDirectory.dir("classes/kotlin/main"),
        layout.buildDirectory.dir("resources/main"),
        configurations.named("sdkForManifestEmission"),
    )

    inputs.files(acpReleaseProps)
    outputs.file(acpManifest)

    val out = acpManifest
    val captured = ByteArrayOutputStream()
    standardOutput = captured

    doFirst {
        out.get().asFile.parentFile.mkdirs()
        // The buffer lives for the whole configuration; a second execution in the same
        // build would APPEND to the first document and emit concatenated JSON.
        captured.reset()
    }

    doLast {
        val text = captured.toString(Charsets.UTF_8)
        if (text.isBlank()) {
            throw GradleException(
                "manifest emission produced no output. The plugin would ship without " +
                    "META-INF/pipelinek/plugin-manifest.json and admission would refuse it at runtime.",
            )
        }
        out.get().asFile.writeText(text)
        println("acp: manifest emitted to " + out.get().asFile)
    }
}

tasks.named("processResources") { finalizedBy(emitAcpManifest) }

tasks.named("jar") { dependsOn(emitAcpManifest) }

// ---------------------------------------------------------------------------------------
// The operator artifact: plugin classes + private ACP machinery in one measured file.
// ---------------------------------------------------------------------------------------
//
// Why ONE fat JAR rather than many `--plugin-jar` entries: `PreLoadPluginAdmission` admits
// every nominated artifact, and an artifact with no manifest is refused by rule ("a plugin
// that declares nothing is refused rather than admitted on trust"). Vendoring the ACP
// runtime into this plugin's own artifact is therefore not a packaging preference — it is
// the only topology in which the nominated file can be admitted at all.
//
// The SDK contracts and everything the host already ships (kotlin-stdlib, coroutines,
// serialization-json) are deliberately NOT bundled: the host's parent-first classloader
// supplies them. Bundling a second copy is the two-authorities split.
val operatorJar = tasks.register<Jar>("operatorJar") {
    group = "acp"
    description = "assemble the single artifact the operator hands to `--plugin-jar`."
    archiveBaseName.set("pipelinek-acp")
    archiveClassifier.set("operator")
    // INCLUDE, not FAIL: several dependency JARs ship the same
    // META-INF/services registration (kotlinx-io, acp-model), and the `eachFile` hook
    // below MERGES those files rather than letting one artifact win by overwrite.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    dependsOn(tasks.jar, emitAcpManifest)
    from(sourceSets["main"].output)
    from(configurations.runtimeClasspath.map { cp -> cp.files.map { f -> zipTree(f) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/MANIFEST.MF")
        exclude("META-INF/LICENSE*", "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        exclude("module-info.class", "META-INF/versions/*/module-info.class")
        exclude("kotlin/**", "kotlinx/coroutines/**", "kotlinx/serialization/**", "kotlinx/json/**")
        // Our contributor registration is the admission seam itself; whatever the plugin's
        // own resources declare is the only answer the host may get. No dependency may
        // ship this path — and if one ever did, INCLUDE would keep the first copy, which is
        // ours (sourceSets.main.output is layered above), so this exclude makes the
        // guarantee explicit instead of dependent on layer order.
        exclude("META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor")
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named("build") { dependsOn(operatorJar) }
