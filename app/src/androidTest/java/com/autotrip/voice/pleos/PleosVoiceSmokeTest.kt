package com.autotrip.voice.pleos

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.autotrip.voice.VoicePrompts
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 에뮬레이터에서 PLEOS TTS·STT가 실제로 동작하는지 확인하는 점검용 테스트.
 * 결과는 assert 대신 기록으로 남긴다 (files/voice-smoke.txt, logcat 태그 VoiceSmoke).
 */
@RunWith(AndroidJUnit4::class)
class PleosVoiceSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val log = StringBuilder()

    private fun record(line: String) {
        Log.i("VoiceSmoke", line)
        log.appendLine("${System.currentTimeMillis() % 100_000} $line")
        File(context.filesDir, "voice-smoke.txt").writeText(log.toString())
    }

    @Test
    fun tts_초기화_후_한국어로_질문을_읽는다() = runBlocking {
        val tts = PleosSpeechOutput(context)
        try {
            // 감싸서 돌려받는다: 바깥 null = 시간 초과, 안쪽 null = 성공
            val prepare = withTimeoutOrNull(15_000) { listOf(tts.prepare()) }
            record("TTS prepare -> ${prepare?.let { it[0] ?: "OK" } ?: "TIMEOUT(15s)"}")
            if (prepare != null && prepare[0] == null) {
                val result = withTimeoutOrNull(20_000) { tts.speak(VoicePrompts.QUESTION) }
                record("TTS speak -> ${result ?: "TIMEOUT(20s)"}")
            }
        } catch (t: Throwable) {
            record("TTS exception -> ${t::class.simpleName}: ${t.message}")
        } finally {
            tts.release()
        }
    }

    @Test
    fun stt_초기화_후_한번_듣는다() = runBlocking {
        val stt = PleosSpeechInput(context)
        try {
            val prepare = withTimeoutOrNull(15_000) { listOf(stt.prepare()) }
            record("STT prepare -> ${prepare?.let { it[0] ?: "OK" } ?: "TIMEOUT(15s)"}")
            if (prepare != null && prepare[0] == null) {
                val result = withTimeoutOrNull(12_000) {
                    stt.listen(onPartial = { partial -> record("STT partial -> $partial") }, onReady = { record("STT onReady") })
                }
                record("STT listen -> ${result ?: "TIMEOUT(12s)"}")
            }
        } catch (t: Throwable) {
            record("STT exception -> ${t::class.simpleName}: ${t.message}")
        } finally {
            stt.release()
        }
    }
}
