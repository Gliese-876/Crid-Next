package cn.crid.next.ui

import org.junit.Assert.*
import org.junit.Test

class WideColorDisplayPolicyTest {
    private val supported = WideColorCapabilities(true, true, true, true)

    @Test fun p3NeedsAnAttachedWideGamutDisplayAndHardwareRendering() {
        assertTrue(supported.supported)
        assertFalse(supported.copy(validDisplay = false).supported)
        assertFalse(supported.copy(hardwareAccelerated = false).supported)
        assertFalse(supported.copy(wideColorRendering = false).supported)
        assertFalse(supported.copy(wideGamutDisplay = false).supported)
        assertFalse(WideColorCapabilities().supported)
    }

    @Test fun supportedForegroundAutomaticallyEnablesP3() {
        val state = WideColorDisplayState(supported = true, displayId = 3)
        assertTrue(state.active)
        assertFalse(state.copy(supported = false).active)
        assertFalse(state.copy(foreground = false).active)
        assertFalse(WideColorDisplayState().active)
        assertEquals(3, state.copy(foreground = false).displayId)
    }
}
