import com.compiler.lexer.Lexer
import com.compiler.parser.Parser
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class E2ETest {

    private fun parseFile(path: String) {
        val source = Files.readString(Paths.get(path))
        val tokens = Lexer(source).tokenize()
        val program = Parser(tokens).parse()
        assertTrue(program.statements.isNotEmpty(), "Program should contain statements")
    }

    @Test
    fun `factorial program`() {
        parseFile("src/test/resources/factorial.lang")
    }

    @Test
    fun `merge sort program`() {
        parseFile("src/test/resources/merge_sort.lang")
    }

    @Test
    fun `sieve program`() {
        parseFile("src/test/resources/prime.lang")
    }

    @Test
    fun `arithmetic program`() {
        parseFile("src/test/resources/simple.lang")
    }
}
