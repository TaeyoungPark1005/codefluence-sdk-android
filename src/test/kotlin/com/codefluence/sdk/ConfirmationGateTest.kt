package com.codefluence.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConfirmationGateTest {
    @Test fun concurrentCallsAdmitOnePromptAndOneRequest() {
        val gate = ConfirmationGate()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val prompts = AtomicInteger()
        val requests = AtomicInteger()
        try {
            val tasks = (1..128).map {
                pool.submit {
                    start.await()
                    if (gate.beginPrompt()) prompts.incrementAndGet()
                    if (gate.beginRequest()) requests.incrementAndGet()
                }
            }
            start.countDown()
            tasks.forEach { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1, prompts.get())
            assertEquals(1, requests.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun releasedPromptCanBeShownAgainWithoutReleasingAnActiveRequest() {
        val gate = ConfirmationGate()
        assertTrue(gate.beginPrompt())
        assertTrue(gate.beginRequest())
        assertFalse(gate.beginPrompt())
        gate.endPrompt()
        assertTrue(gate.beginPrompt())
        assertFalse(gate.beginRequest())
        gate.endRequest()
        assertTrue(gate.beginRequest())
    }
}
