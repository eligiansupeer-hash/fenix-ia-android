package com.fenix.ia

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fenix.ia.data.local.security.SecureApiManager
import com.fenix.ia.domain.model.ApiProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProvisionDeviceInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun guardaClavesApiEnKeystoreDelDispositivo() = runBlocking {
        val gemini = args.getString("GEMINI_KEY").orEmpty()
        val groq = args.getString("GROQ_KEY").orEmpty()
        assumeTrue("GEMINI_KEY no provista", gemini.isNotBlank())
        assumeTrue("GROQ_KEY no provista", groq.isNotBlank())

        val secureApiManager = SecureApiManager(context)
        secureApiManager.saveApiKey(ApiProvider.GEMINI, gemini)
        secureApiManager.saveApiKey(ApiProvider.GROQ, groq)

        assertTrue(secureApiManager.getDecryptedKey(ApiProvider.GEMINI) == gemini)
        assertTrue(secureApiManager.getDecryptedKey(ApiProvider.GROQ) == groq)
    }
}
