package com.codefluence.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SDK 0.2.1: 모든 서버 요청에 `build: "debug" | "release"`를 싣는다. 판정은 호스트 앱의 `FLAG_DEBUGGABLE`이며
 * 서버는 `"debug"` 신호를 운영 집계·보상에서 뺀다. 에뮬레이터는 판정하지 않는다(실사용자 오탐 방지).
 */
class BuildKindTest {
    private val signed = "6902082a-b202-4f7f-9b61-6ec419fb3b81.481e73f0-b627-482a-9466-5bc004807ff9.1796533770.UTM7ns4CUi-k-rI8rPXizYq5kjXadqEi5hP_jSA2JYM"
    private fun sdk(transport: ScriptedTransport, debuggable: Boolean?, prefs: Map<String, Any> = emptyMap()) =
        Codefluence.createForTest("pk_fixture", "https://api.example.test", TestPreferences(prefs), transport, debuggable = debuggable).also { it.schemeRegistered = { true } }

    @Test fun debuggableHostIsDebugAndEverythingElseIsRelease() {
        assertEquals("debug", sdk(ScriptedTransport(), debuggable = true).build)
        assertEquals("release", sdk(ScriptedTransport(), debuggable = false).build)
        assertEquals("Context가 없으면(판정 불가) 운영 빌드로 본다", "release", sdk(ScriptedTransport(), debuggable = null).build)
    }

    @Test fun heartbeatAndValidateCarryTheBuildKind() {
        val transport = ScriptedTransport().also { it.reply = { CodefluenceHttpResponse(200, "{}") } }
        val client = sdk(transport, debuggable = true)
        client.heartbeat(appVersion = "1.0.0") { }
        var checks: List<CodefluenceCheck> = emptyList()
        client.validate { checks = it }
        val beats = transport.captured.filter { it.url.endsWith("/sdk/heartbeat") }.map { JSONObject(it.body) }
        assertEquals(2, beats.size)
        assertTrue(beats.all { it.getString("build") == "debug" && it.getString("sdkVersion") == "0.2.2" })
        val build = checks.first { it.name == "빌드" }
        assertTrue("빌드 종류는 안내 항목이라 점검 실패로 치지 않는다", build.passed)
        assertTrue(build.hint, build.hint.contains("개발 빌드") && build.hint.contains("운영 집계") && build.hint.contains("들어가지 않아요"))
    }

    @Test fun releaseValidateSaysSignalsCount() {
        val transport = ScriptedTransport().also { it.reply = { CodefluenceHttpResponse(200, "{}") } }
        var checks: List<CodefluenceCheck> = emptyList()
        sdk(transport, debuggable = false).validate { checks = it }
        assertTrue(checks.first { it.name == "빌드" }.hint.contains("출시 빌드"))
        assertEquals("release", JSONObject(transport.captured.single().body).getString("build"))
    }

    @Test fun confirmationCarriesTheBuildKind() {
        val transport = ScriptedTransport()
        sdk(transport, debuggable = true, prefs = mapOf("clickToken" to signed)).autoConfirmIfAvailable("user-1") { }
        val confirm = JSONObject(transport.captured.first { it.url.endsWith("/track/confirm") }.body)
        assertEquals("debug", confirm.getString("build"))
    }
}
