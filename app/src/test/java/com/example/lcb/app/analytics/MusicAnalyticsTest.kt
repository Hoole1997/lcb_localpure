package com.example.lcb.app.analytics

import com.example.lcb.music.model.MusicPlatform
import net.corekit.core.report.ReportDataManager
import net.corekit.core.report.ReporterData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicAnalyticsTest {
    @Test
    fun `playback error code is reported as string`() {
        val parameters = MusicAnalytics.playbackErrorParameters(
            platform = MusicPlatform.AUDIUS,
            errorCode = 2004,
        )

        assertEquals("audius", parameters["platform"])
        assertEquals("2004", parameters["error_code"])
        assertTrue(parameters["error_code"] is String)
    }

    @Test
    fun `missing or blank playback error code falls back to 500`() {
        assertEquals(
            "500",
            MusicAnalytics.playbackErrorParameters(MusicPlatform.JAMENDO, null)["error_code"],
        )
        assertEquals(
            "500",
            MusicAnalytics.playbackErrorParameters(MusicPlatform.JAMENDO, "")["error_code"],
        )
        assertEquals(
            "500",
            MusicAnalytics.playbackErrorParameters(MusicPlatform.JAMENDO, "   ")["error_code"],
        )
    }

    @Test
    fun `apply language always contains a normalized value`() {
        assertEquals(
            "zh-cn",
            MusicAnalytics.settingsParameters(
                action = MusicAnalytics.SettingsAction.APPLY_LANGUAGE,
                outcome = MusicAnalytics.Outcome.SUCCESS,
                value = "zh-CN",
            )["value"],
        )
        assertEquals(
            "system",
            MusicAnalytics.settingsParameters(
                action = MusicAnalytics.SettingsAction.APPLY_LANGUAGE,
                outcome = MusicAnalytics.Outcome.SUCCESS,
                value = null,
            )["value"],
        )
        assertEquals(
            "system",
            MusicAnalytics.settingsParameters(
                action = MusicAnalytics.SettingsAction.APPLY_LANGUAGE,
                outcome = MusicAnalytics.Outcome.SUCCESS,
                value = "   ",
            )["value"],
        )
    }

    @Test
    fun `apply language sends non null value through report data manager`() {
        val reporter = CapturingReporter()
        ReportDataManager.setReporters(listOf(reporter))
        try {
            MusicAnalytics.settings(
                action = MusicAnalytics.SettingsAction.APPLY_LANGUAGE,
                outcome = MusicAnalytics.Outcome.SUCCESS,
                value = "de",
            )

            assertEquals("music_settings_action", reporter.eventName)
            assertEquals("apply_language", reporter.parameters["action"])
            assertEquals("de", reporter.parameters["value"])
        } finally {
            ReportDataManager.setReporters(emptyList())
        }
    }

    private class CapturingReporter : ReporterData {
        var eventName: String? = null
        var parameters: Map<String, Any> = emptyMap()

        override fun getName() = "test"

        override fun reportData(eventName: String, data: Map<String, Any>) {
            this.eventName = eventName
            parameters = data
        }

        override fun setCommonParams(params: Map<String, Any>) = Unit

        override fun setUserParams(params: Map<String, Any>) = Unit
    }
}
