package com.codefluence.sdk

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ExplicitHostTest {
    @Test fun publicConfigureRequiresHostAndHasNoKotlinDefaultHostEntryPoint() {
        // Inspect the actual compiled public API without creating an Android
        // Activity, storage, worker or network connection in this JVM test.
        val methods = Codefluence.Companion::class.java.declaredMethods
        val configure = methods.single { it.name == "configure" }
        assertEquals(listOf(Context::class.java, String::class.java, String::class.java), configure.parameterTypes.toList())
        assertEquals(Codefluence::class.java, configure.returnType)
        assertFalse("configure must not substitute an unowned host", methods.any { it.name == "configure\$default" })
    }

    @Test fun publicConfigureRejectsSecretKeysAndNonHttpHostsBeforeUsingContext() {
        assertThrows(IllegalArgumentException::class.java) {
            Codefluence.configure(Application(), "sk_live_fixture", "https://sdk-test.invalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Codefluence.configure(Application(), "pk_fixture", "file:///tmp/codefluence")
        }
    }

    @Test fun publicConfigureRequiresAnOriginWithoutCredentialsPathQueryOrFragment() {
        listOf(
            "https://user:pass@sdk-test.invalid",
            "https://sdk-test.invalid/tenant",
            "https://sdk-test.invalid?token=secret",
            "https://sdk-test.invalid/#fragment"
        ).forEach { host ->
            assertThrows(IllegalArgumentException::class.java) {
                Codefluence.configure(Application(), "pk_fixture", host)
            }
        }
    }
}
