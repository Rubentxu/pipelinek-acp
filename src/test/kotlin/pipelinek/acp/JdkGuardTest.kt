package pipelinek.acp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.copyRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The build's JDK guard, measured by RUNNING a build the guard guards.
 *
 * The guard lives in this repository's settings script, so the fixture below is not a
 * re-implementation of it: the fixture IS this repository's entry points, copied as they
 * are. That is what makes the rows meaningful — a guard that stopped being applied, or a
 * refusal that stopped naming the three things a reader needs, falls here.
 *
 * The two rows are the two halves of the contract: a daemon on a JDK this project does not
 * declare is refused, and a daemon on the declared JDK builds. The rows assert the FORM of
 * the refusal — that it names the JDK it expects, the JDK it found, and a path that fixes
 * it — because a message nobody can act on is the failure this guard exists to replace.
 */
class JdkGuardTest {

    @Test
    fun `the build refuses a daemon JDK that is not the declared one`() {
        val declared = declaredToolchainMajor()
        // The highest JDK this machine has that is not the declared one: the refusal is
        // about ANY other JDK, and the newest one is the one a reader is most likely to
        // be running.
        val wrong = jdkInstallations().lastOrNull { it.first != declared }
        assumeTrue(
            wrong != null,
            "this machine has no JDK other than the declared one: the refusal cannot be measured",
        )
        val (foundMajor, foundHome) = requireNotNull(wrong)

        val build = runFixtureBuild(foundHome)

        assertTrue(build.exitCode != 0, "the build must refuse a JDK $foundMajor daemon:\n${build.output}")
        assertTrue(
            Regex("""\bJDK\s+$declared\b""").containsMatchIn(build.output),
            "the refusal must name the JDK it expects (JDK $declared):\n${build.output}",
        )
        assertTrue(
            Regex("""\bJDK\s+$foundMajor\b""").containsMatchIn(build.output),
            "the refusal must name the JDK it found (JDK $foundMajor):\n${build.output}",
        )
        val fix = Regex("""JAVA_HOME=(\S+)""").find(build.output)
        assertTrue(fix != null, "the refusal must say how to fix it, naming a path:\n${build.output}")
        val fixed = Path.of(requireNotNull(fix).groupValues[1])
        assertTrue(Files.isDirectory(fixed), "the path the refusal names must exist: $fixed")
    }

    @Test
    fun `the build accepts the declared JDK`() {
        val declared = declaredToolchainMajor()
        // This JVM runs the build, so it runs on the declared JDK: the guard would have
        // refused the build otherwise, and this row would have nothing to measure.
        val home = Path.of(System.getProperty("java.home"))
        assertTrue(
            jdkMajorOf(home) == declared,
            "this test runs on JDK ${System.getProperty("java.version")}, not the declared JDK $declared",
        )

        val build = runFixtureBuild(home)

        assertTrue(build.exitCode == 0, "the build must pass on the declared JDK:\n${build.output}")
    }

    /** The JDK this project declares, read from the property the build and the guard share. */
    private fun declaredToolchainMajor(): Int {
        val properties = repositoryPath().resolve("gradle.properties")
        val declared = Regex("""^pipelinek\.acp\.jvmToolchain=(\d+)$""", RegexOption.MULTILINE)
            .find(Files.readString(properties))
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
        return requireNotNull(declared) {
            "gradle.properties declares no JDK for this build to compile with"
        }
    }

    /** The JDK installations this machine has, by major version. */
    private fun jdkInstallations(): List<Pair<Int, Path>> {
        val roots = listOf(
            Path.of(System.getProperty("user.home"), ".asdf", "installs", "java"),
            Path.of(System.getProperty("user.home"), ".sdkman", "candidates", "java"),
            Path.of("/usr/lib/jvm"),
            Path.of("/Library/Java/JavaVirtualMachines"),
        )
        return roots
            .filter { Files.isDirectory(it) }
            .flatMap { root ->
                Files.list(root).use { stream -> stream.filter { Files.isDirectory(it) }.toList() }
            }
            .map { installation ->
                // macOS bundles the JDK under Contents/Home.
                val home = installation.resolve("Contents/Home")
                    .takeIf { Files.isDirectory(it) } ?: installation
                home to jdkMajorOf(home)
            }
            .filter { (_, major) -> major != null }
            .map { (home, major) -> requireNotNull(major) to home }
            .sortedBy { it.first }
    }

    /** The major version of a JDK installation, read from the JDK's own release file. */
    private fun jdkMajorOf(home: Path): Int? {
        val release = home.resolve("release")
        if (!Files.isRegularFile(release)) {
            return null
        }
        return Regex("""JAVA_VERSION="?(\d+)""")
            .find(Files.readString(release))
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    /**
     * Runs a build of this repository's entry points on the given JDK.
     *
     * The fixture is the repository itself, copied: the settings script that holds the
     * guard, the property that declares the JDK, the guard itself and the wrapper this
     * repository ships. Nothing about the refusal is simulated here — the build either
     * refuses or it does not.
     */
    private fun runFixtureBuild(jdkHome: Path): BuildResult {
        val repository = repositoryPath()
        val fixture = repository.resolve("build/tmp/jdk-guard-test")
        // A fresh fixture per build: the nested build writes into it, and a second build
        // must not read the first one's leftovers.
        if (Files.exists(fixture)) {
            fixture.toFile().deleteRecursively()
        }
        fixture.createDirectories()
        for (file in listOf("settings.gradle", "gradle.properties")) {
            Files.copy(
                repository.resolve(file),
                fixture.resolve(file),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        repository.resolve("gradle").toFile().copyRecursively(fixture.resolve("gradle").toFile())
        repository.resolve("gradlew").toFile().copyTo(fixture.resolve("gradlew").toFile())
        fixture.resolve("gradlew").toFile().setExecutable(true)
        fixture.resolve("build.gradle.kts").writeText(FIXTURE_BUILD_SCRIPT)

        val process = ProcessBuilder(
            fixture.resolve("gradlew").toString(),
            // A single-use daemon: the nested build must not leave a JVM behind, and it
            // must run on the JDK under test, not on the one this test happens to run on.
            "--no-daemon",
            "fixture",
        )
            .directory(fixture.toFile())
            .redirectErrorStream(true)
            .apply { environment()["JAVA_HOME"] = jdkHome.toString() }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return BuildResult(process.waitFor(), output)
    }

    private fun repositoryPath(): Path =
        Path.of(requireNotNull(System.getProperty("acp.projectDir")) {
            "the build must tell this test where the repository is"
        })

    /** The exit code and the whole output of a nested build. */
    private data class BuildResult(val exitCode: Int, val output: String)

    private companion object {
        /**
         * The fixture's build script. It is a Kotlin DSL script on purpose: that is the
         * script a daemon on an unsupported JDK cannot compile, so a build WITHOUT the
         * guard reproduces the symptom the guard replaces.
         */
        const val FIXTURE_BUILD_SCRIPT = """
            tasks.register("fixture") {
                doLast { println("fixture task ran") }
            }
        """
    }
}
