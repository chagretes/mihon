package eu.kanade.tachiyomi.ui.reader.viewer.guided

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class GuidedPageStateTest {

    @Test
    fun `tapping a region focuses it and tapping it again restores the page`() {
        val state = GuidedPageState()
        state.setRegions(regions)

        assertEquals(GuidedPageState.Step.Focus(regions[1]), state.toggleRegion(1))
        assertEquals(1, state.currentIndex)
        assertEquals(GuidedPageState.Step.ShowWholePage, state.toggleRegion(1))
        assertEquals(-1, state.currentIndex)
    }

    @Test
    fun `tapping another region changes the focused region`() {
        val state = GuidedPageState()
        state.setRegions(regions)

        state.toggleRegion(0)

        assertEquals(GuidedPageState.Step.Focus(regions[1]), state.toggleRegion(1))
        assertEquals(1, state.currentIndex)
    }

    @Test
    fun `forward waits for detection then focuses each region before leaving`() {
        val state = GuidedPageState()
        state.markLoading()

        assertEquals(GuidedPageState.Step.Wait, state.forward())
        assertInstanceOf(GuidedPageState.Step.Focus::class.java, state.setRegions(regions))
        assertEquals(0, state.currentIndex)
        assertInstanceOf(GuidedPageState.Step.Focus::class.java, state.forward())
        assertEquals(1, state.currentIndex)
        assertEquals(
            GuidedPageState.Step.LeavePage(GuidedPageState.Direction.FORWARD),
            state.forward(),
        )
    }

    @Test
    fun `backward shows the whole page before leaving`() {
        val state = GuidedPageState()
        state.setRegions(regions)
        state.forward()
        state.forward()

        assertInstanceOf(GuidedPageState.Step.Focus::class.java, state.backward())
        assertEquals(GuidedPageState.Step.ShowWholePage, state.backward())
        assertEquals(
            GuidedPageState.Step.LeavePage(GuidedPageState.Direction.BACKWARD),
            state.backward(),
        )
    }

    @Test
    fun `can select the last region before detection finishes`() {
        val state = GuidedPageState()
        state.markLoading()
        state.selectLastRegion()

        val step = state.setRegions(regions)

        assertEquals(1, state.currentIndex)
        assertInstanceOf(GuidedPageState.Step.Focus::class.java, step)
    }

    private val regions = listOf(
        GuidedRegion(NormalizedRect(0f, 0f, 0.4f, 0.2f), 0.9f),
        GuidedRegion(NormalizedRect(0.5f, 0f, 0.9f, 0.2f), 0.8f),
    )
}
