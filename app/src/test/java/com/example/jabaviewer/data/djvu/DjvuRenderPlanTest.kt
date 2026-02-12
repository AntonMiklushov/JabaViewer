package com.example.jabaviewer.data.djvu

import org.junit.Assert.assertEquals
import org.junit.Test

class DjvuRenderPlanTest {
    @Test
    fun buildViewerRenderPlan_usesFullSourcePageAndTargetSize() {
        val plan = buildViewerRenderPlan(
            pageWidthPx = 2000,
            pageHeightPx = 3000,
            requestedWidthPx = 1000,
        )

        assertEquals(0, plan.sourceRect.left)
        assertEquals(0, plan.sourceRect.top)
        assertEquals(2000, plan.sourceRect.width)
        assertEquals(3000, plan.sourceRect.height)
        assertEquals(2000, plan.destRect.width)
        assertEquals(3000, plan.destRect.height)
        assertEquals(1000, plan.targetRect.width)
        assertEquals(1500, plan.targetRect.height)
        assertEquals(1000, plan.outputWidthPx)
        assertEquals(1500, plan.outputHeightPx)
    }

    @Test
    fun buildViewerRenderPlan_doesNotUpscalePastSourceWidth() {
        val plan = buildViewerRenderPlan(
            pageWidthPx = 1200,
            pageHeightPx = 1800,
            requestedWidthPx = 2000,
        )

        assertEquals(1200, plan.outputWidthPx)
        assertEquals(1800, plan.outputHeightPx)
        assertEquals(1200, plan.destRect.width)
        assertEquals(1800, plan.destRect.height)
    }

    @Test
    fun buildViewerRenderPlan_keepsNativeScaleAtOrAboveMinScale() {
        val plan = buildViewerRenderPlan(
            pageWidthPx = 2479,
            pageHeightPx = 3508,
            requestedWidthPx = 884,
        )

        assertEquals(884, plan.targetRect.width)
        assertEquals(1251, plan.targetRect.height)
        assertEquals(2479, plan.destRect.width)
        assertEquals(3508, plan.destRect.height)
    }

    @Test
    fun buildViewerRenderPlan_withLowerNativeFloor_usesIntermediateDownscale() {
        val plan = buildViewerRenderPlan(
            pageWidthPx = 2479,
            pageHeightPx = 3508,
            requestedWidthPx = 884,
            minNativeScale = LOWEST_NATIVE_SCALE_FLOOR,
        )

        assertEquals(1240, plan.destRect.width)
        assertEquals(1755, plan.destRect.height)
    }

    @Test
    fun computeAspectHeight_preservesAspectRatioFromWidth() {
        val targetHeight = computeAspectHeight(
            sourceWidthPx = 2479,
            sourceHeightPx = 3508,
            targetWidthPx = 884,
        )

        assertEquals(1251, targetHeight)
    }
}
