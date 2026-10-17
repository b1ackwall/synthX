package com.v1per.synthx.testing

import com.v1per.synthx.ui.testing.TestTimeouts
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/** The comment stripper behind the wiring tests: comments go, code and literals stay. */
class ProductionSourceTest {
    @get:Rule(order = TestTimeouts.RULE_ORDER) val testTimeout = TestTimeouts.jvm()

    @Test fun lineAndBlockCommentsAreRemoved() {
        val source = "a() // b()\n/* c()\n d() */ e() /** f() */\n"
        assertEquals("a() \n  e()  \n", ProductionSource.codeOnly(source))
    }

    @Test fun commentMarkersInsideLiteralsAreKept() {
        val source = "val u = \"content://x/*y*/\"; val c = '/' // gone\nval r = \"\"\"// kept\"\"\""
        assertEquals(
            "val u = \"content://x/*y*/\"; val c = '/' \nval r = \"\"\"// kept\"\"\"",
            ProductionSource.codeOnly(source),
        )
    }

    @Test fun escapedQuotesDoNotEndALiteral() {
        val source = "\"a\\\"// still text\" // gone"
        assertEquals("\"a\\\"// still text\" ", ProductionSource.codeOnly(source))
    }
}
