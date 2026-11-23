package app.venkat.filetools.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File

/** The texts shipped as assets must stay byte-for-byte identical to the repository files. */
class LicenceAssetsTest {
    /** Gradle runs unit tests in the module directory (`app/`); also accept the repo root. */
    private fun repoFile(name: String): File =
        listOf(File("../$name"), File(name))
            .firstOrNull { it.isFile && File(it.absoluteFile.parentFile, "settings.gradle.kts").isFile }
            ?: error("$name not found from ${File(".").absolutePath}")

    private fun asset(name: String): File =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).firstOrNull { it.isFile }
            ?: error("asset $name not found")

    @Test
    fun shippedLicenseEqualsRepoLicense() {
        assertArrayEquals(repoFile("LICENSE").readBytes(), asset("LICENSE").readBytes())
    }

    @Test
    fun shippedNoticesEqualRepoNotices() {
        assertArrayEquals(
            repoFile("THIRD_PARTY_NOTICES.md").readBytes(),
            asset("THIRD_PARTY_NOTICES.md").readBytes(),
        )
    }

    @Test
    fun shippedPrivacyPolicyEqualsRepoPolicy() {
        assertArrayEquals(repoFile("PRIVACY.md").readBytes(), asset("PRIVACY.md").readBytes())
    }

    /** Shown as plain text in the app: no markdown tables, code fences or `#` headings. */
    @Test
    fun privacyPolicyReadsAsPlainText() {
        val lines = repoFile("PRIVACY.md").readLines()
        lines.forEach { line ->
            check(!line.startsWith("#") && !line.contains("|") && !line.startsWith("```")) { "markdown syntax: $line" }
        }
        check(lines.any { "chennakrishnaa@yahoo.com" in it })
    }
}
