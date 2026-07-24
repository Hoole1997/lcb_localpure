package com.example.lcb.app.player

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerTrackFlipViewTest {
    @Test
    fun textFaceUsesFullStageWhileArtworkRemainsSquare() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val stage = PlayerTrackFlipView(instrumentation.targetContext)
            val stageWidth = 1_080
            val stageHeight = 1_500
            stage.measure(exactly(stageWidth), exactly(stageHeight))
            stage.layout(0, 0, stage.measuredWidth, stage.measuredHeight)

            val artworkFace = stage.getChildAt(0)
            val textFace = stage.getChildAt(1)
            assertEquals(stageWidth, textFace.width)
            assertEquals(stageHeight, textFace.height)
            assertEquals(artworkFace.width, artworkFace.height)
            assertTrue(textFace.height > artworkFace.height)
        }
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
