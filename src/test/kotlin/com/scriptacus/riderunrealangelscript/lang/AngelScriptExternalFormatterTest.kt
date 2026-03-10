package com.scriptacus.riderunrealangelscript.lang

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.UsefulTestCase
import java.io.File

class AngelScriptExternalFormatterTest : UsefulTestCase() {

    private val builder = AngelScriptExternalFormatter()

    fun testAllowEditRejectsSpaceBetweenStringPrefixAndLiteral() {
        val line = "auto value = f\"hello\";"
        val quoteOffset = line.indexOf('"')

        val allowed = builder.allowEditForTest(
            fileText = line,
            offset = quoteOffset,
            length = 0,
            replacementText = " ",
            formattingRange = TextRange(0, line.length)
        )

        assertFalse("Should reject space between string prefix and literal", allowed)
    }

    fun testAllowEditRejectsSpaceBetweenAmpersandAndInKeyword() {
        val line = "void Test(&in value) {}"
        val inOffset = line.indexOf("in value")

        val allowed = builder.allowEditForTest(
            fileText = line,
            offset = inOffset,
            length = 0,
            replacementText = " ",
            formattingRange = TextRange(0, line.length)
        )

        assertFalse("Should reject space between '&' and 'in'", allowed)
    }

    fun testAllowEditAllowsRegularWhitespaceEdit() {
        val line = "int value=10;"
        val offset = line.indexOf('=')

        val allowed = builder.allowEditForTest(
            fileText = line,
            offset = offset,
            length = 0,
            replacementText = " ",
            formattingRange = TextRange(0, line.length)
        )

        assertTrue("Should allow regular spacing edits", allowed)
    }

    fun testAllowEditRejectsKeywordIdentifierLineBreak() {
        val line = "private MyType value;"
        val breakOffset = line.indexOf(" MyType")

        val allowed = builder.allowEditForTest(
            fileText = line,
            offset = breakOffset,
            length = 1,
            replacementText = "\n",
            formattingRange = TextRange(0, line.length)
        )

        assertFalse("Should reject line break that splits 'keyword type name'", allowed)
    }

    fun testAllowEditRejectsEditsOutsideFormattingRange() {
        val line = "int value=10;"
        val offset = line.indexOf('=')

        val allowed = builder.allowEditForTest(
            fileText = line,
            offset = offset,
            length = 0,
            replacementText = " ",
            formattingRange = TextRange(0, offset - 1)
        )

        assertFalse("Should reject edits outside requested formatting range", allowed)
    }

    fun testResolveStyleFilePathUsesProjectScriptRoot() {
        val filePath = "C:\\Projects\\MyGame\\Script\\Gameplay\\Test.as"

        val resolved = builder.resolveStyleFilePathForTest(filePath)

        assertEquals(
            "Should use .clang-format from the project Script root",
            File("C:\\Projects\\MyGame\\Script", ".clang-format").path,
            resolved
        )
    }

    fun testResolveStyleFilePathUsesPluginScriptRoot() {
        val filePath = "C:\\Projects\\MyGame\\Plugins\\MyPlugin\\Script\\Gameplay\\Test.as"

        val resolved = builder.resolveStyleFilePathForTest(filePath)

        assertEquals(
            "Should use .clang-format from the plugin Script root",
            File("C:\\Projects\\MyGame\\Plugins\\MyPlugin\\Script", ".clang-format").path,
            resolved
        )
    }

    fun testResolveStyleFilePathFallsBackWhenNoScriptRootFound() {
        val filePath = "C:\\Projects\\MyGame\\Source\\Gameplay\\Test.as"

        val resolved = builder.resolveStyleFilePathForTest(filePath)

        assertEquals(
            "Should fall back to default clang-format style lookup",
            ".clang-format",
            resolved
        )
    }

    fun testApplyReplacementsProducesExpectedText() {
        val original = "int value=10;"
        val equalsOffset = original.indexOf('=')

        val formatted = builder.applyReplacementsForTest(
            original,
            listOf(Triple(equalsOffset, 1, " = "))
        )

        assertEquals("Replacements should be applied to produce formatted text", "int value = 10;", formatted)
    }
}
