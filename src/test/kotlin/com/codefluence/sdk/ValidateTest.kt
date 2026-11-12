package com.codefluence.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 설치 점검(validate)과 heartbeat 계약. 출시 전에 키·서버·스킴이 맞는지 한 번에 확인하고,
 * 서버에는 첫 신호가 바로 도착해야 한다(서버 스키마는 platform을 요구한다).
 */
class ValidateTest {
    private fun sdk(key: String = "pk_fixture", transport: CodefluenceTransport, registeredScheme: Boolean = true): Codefluence {
        val sdk = Codefluence.createForTest(key, "https://api.example.test", TestPreferences(), transport)
        sdk.schemeRegistered = { registeredScheme }
        return sdk
    }
    private fun validate(sdk: Codefluence, scheme: String? = null): List<CodefluenceCheck> {
        var result: List<CodefluenceCheck> = emptyList()
        sdk.validate(scheme) { result = it }
        return result
    }
    private fun status(code: Int) = ScriptedTransport().also { it.reply = { CodefluenceHttpResponse(code, "{}") } }
    private fun List<CodefluenceCheck>.named(name: String) = first { it.name == name }

    @Test fun healthySetupPassesEveryCheck() {
        val transport = status(200)
        val checks = validate(sdk(transport = transport), "adledger")
        assertEquals(listOf("설정", "서버와 키", "빌드", "URL 스킴"), checks.map { it.name })
        assertTrue(checks.toString(), checks.all { it.passed })
        assertEquals("점검이 첫 신호로도 도착해야 한다", 1, transport.captured.count { it.url.endsWith("/sdk/heartbeat") })
    }

    @Test fun rejectedKeyExplainsWhatToCheck() {
        val server = validate(sdk(transport = status(401))).named("서버와 키")
        assertFalse(server.passed)
        assertTrue(server.hint, server.hint.contains("pk_") && server.hint.contains("폐기"))
    }

    @Test fun platformMismatchExplainsWhichProductKeyToUse() {
        val server = validate(sdk(transport = status(422))).named("서버와 키")
        assertFalse(server.passed)
        assertTrue(server.hint, server.hint.contains("플랫폼"))
    }

    @Test fun unreachableServerPointsAtTheNetworkAndHost() {
        val transport = ScriptedTransport().also { it.reply = { ScriptedTransport.transportFailure() } }
        val server = validate(sdk(transport = transport)).named("서버와 키")
        assertFalse(server.passed)
        assertTrue(server.hint, server.hint.contains("주소"))
    }

    @Test fun missingUrlSchemeIsReportedWithTheFix() {
        val scheme = validate(sdk(transport = status(200), registeredScheme = false), "adledger").named("URL 스킴")
        assertFalse(scheme.passed)
        assertTrue(scheme.hint, scheme.hint.contains("intent-filter") && scheme.hint.contains("adledger"))
    }

    @Test fun secretKeyShapeFailsLocallyWithoutANetworkCall() {
        val transport = status(200)
        val checks = validate(sdk(key = "sk_live_wrong", transport = transport))
        assertFalse(checks.named("설정").passed)
        assertTrue(checks.named("설정").hint.contains("sk_"))
        assertEquals(0, transport.requestCount)
    }

    @Test fun heartbeatBodyMatchesTheServerContract() {
        val transport = status(200)
        sdk(transport = transport).heartbeat(appVersion = "1.2.3") { }
        val body = JSONObject(transport.captured.single { it.url.endsWith("/sdk/heartbeat") }.body)
        assertEquals("android", body.getString("platform"))
        assertEquals("1.2.3", body.getString("appVersion"))
        assertNotNull(body.getString("sdkVersion"))
        assertFalse("증명이 없으면 키를 빼야 한다", body.has("attestation"))
    }

    @Test fun beginSendsTheFirstSignalOncePerDay() {
        val transport = status(200)
        val client = sdk(transport = transport)
        client.begin(appUserId = { null }, launchGraceMs = 3_600_000, schedule = { _, _ -> })
        client.begin(appUserId = { null }, launchGraceMs = 3_600_000, schedule = { _, _ -> })
        assertEquals("시작만 해도 SDK 신호가 가야 하고 같은 날 두 번 보내지 않는다", 1, transport.captured.count { it.url.endsWith("/sdk/heartbeat") })
    }
}

class ConfirmationRejectedTest {
    @Test fun serverRejectionOfAnotherInstallIsADedicatedNonRetryableError() {
        val transport = ScriptedTransport().also { it.reply = { CodefluenceHttpResponse(409, "{\"error\":\"confirmation_rejected\"}") } }
        val signed = "6902082a-b202-4f7f-9b61-6ec419fb3b81.481e73f0-b627-482a-9466-5bc004807ff9.1796533770.UTM7ns4CUi-k-rI8rPXizYq5kjXadqEi5hP_jSA2JYM"
        val sdk = Codefluence.createForTest("pk_fixture", "https://api.example.test", TestPreferences(mapOf("clickToken" to signed)), transport)
        var result: Result<String?>? = null
        sdk.autoConfirmIfAvailable("user-1") { result = it }
        val error = result!!.exceptionOrNull()
        org.junit.Assert.assertTrue("got: $error / transport=${transport.captured.map { it.url }}", error is CodefluenceError.ConfirmationRejected)
        org.junit.Assert.assertEquals(14, (error as CodefluenceError).code)
        org.junit.Assert.assertFalse(error.isRetryable)
    }
}
