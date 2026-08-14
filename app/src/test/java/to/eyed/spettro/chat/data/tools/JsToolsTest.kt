package to.eyed.spettro.chat.data.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import to.eyed.spettro.chat.data.artifacts.ArtifactRef

/**
 * The Rhino sandbox runs on the plain JVM exactly as it does on-device
 * (interpreted mode, no Java bridge), so these are real end-to-end runs of
 * the run-javascript tool.
 */
class JsToolsTest {
    private val saved = mutableListOf<Pair<String, String>>()
    private val tools = JsTools { name, content ->
        saved += name to content
        ArtifactRef(ArtifactRef.KIND_FILE, name)
    }

    private fun run(code: String): ToolResult =
        tools.run("""{"code":${kotlinx.serialization.json.JsonPrimitive(code)}}""")

    @Test
    fun `evaluates the last expression`() {
        val result = run("const xs = [1,2,3,4]; xs.reduce((a,b)=>a+b, 0)")
        assertFalse(result.isError)
        assertTrue(result.output, result.output.contains("Result: 10"))
    }

    @Test
    fun `captures console output`() {
        val result = run("console.log('hello', 42); undefined")
        assertFalse(result.isError)
        assertTrue(result.output, result.output.contains("hello 42"))
    }

    @Test
    fun `objects come back as JSON`() {
        val result = run("({name: 'x', n: 2})")
        assertFalse(result.isError)
        assertTrue(result.output, result.output.contains("\"name\": \"x\""))
    }

    @Test
    fun `es6 features work`() {
        val result = run(
            "let m = new Map([['a',1],['b',2]]); Array.from(m.keys()).map(k => `k=${'$'}{k}`).join(',')",
        )
        assertFalse(result.output, result.isError)
        assertTrue(result.output, result.output.contains("k=a,k=b"))
    }

    @Test
    fun `infinite loops hit the deadline`() {
        val result = run("while(true) {}")
        assertTrue(result.isError)
        assertTrue(result.output, result.output.contains("limit"))
    }

    @Test
    fun `java is unreachable`() {
        for (probe in listOf(
            "java.lang.System.exit(0)",
            "Packages.java.io.File('/')",
            "new java.io.File('/')",
        )) {
            val result = run(probe)
            assertTrue("expected error for: $probe → ${result.output}", result.isError)
        }
    }

    @Test
    fun `syntax errors are reported, not thrown`() {
        val result = run("const = broken")
        assertTrue(result.isError)
    }

    @Test
    fun `saveFile writes an artifact and reports it`() {
        val result = run("saveFile('data.csv', 'a,b\\n1,2'); 'done'")
        assertFalse(result.output, result.isError)
        assertTrue(result.output, result.output.contains("artifact://file/data.csv"))
        assertTrue(saved.single().second.startsWith("a,b"))
    }
}
