package app.crimera.util

import app.morphe.patcher.util.smali.InlineSmaliCompiler

/**
 * Regression check for the exact class of on-device Morphe failure hit by
 * SafeX v0.6. The cached URT collector is a large instance coroutine method,
 * so p0/p1 live above the 4-bit register range used by normal invoke-35c.
 * invoke-static/range must compile correctly with a large register file.
 */
fun main() {
    val cachedSnippet =
        """
        invoke-static/range {p0 .. p1}, Lapp/morphe/extension/twitter/safex/SafeXListFilter;->filterCachedItems(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;
        move-result-object p1
        """.trimIndent()

    val instructions =
        InlineSmaliCompiler.compile(
            instructions = cachedSnippet,
            parameters = "Ljava/lang/Object;Lkotlin/coroutines/Continuation;",
            registers = 48,
            forStaticMethod = false,
        )

    check(instructions.size == 2) {
        "SafeX cached-list smali regression: expected 2 instructions, got ${instructions.size}"
    }

    println("SafeX cached-list inline smali check: PASS")
}
