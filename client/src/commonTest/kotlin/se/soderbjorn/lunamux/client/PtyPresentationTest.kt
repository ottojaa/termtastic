/**
 * Tests for [PtyPresentation] and [ptyConnectQuery] — the shared passive-mirror
 * mode machine, scale/font math, ambient-report classifier, and connect-URL
 * builder that Android/web/iOS all depend on.
 */
package se.soderbjorn.lunamux.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PtyPresentationTest {

    private val esc = "\u001b"

    @Test
    fun connectQueryIncludesGridWhenPresent() {
        assertEquals("&posture=viewer&cols=80&rows=24", ptyConnectQuery("viewer", 80 to 24))
        assertEquals("&posture=driver", ptyConnectQuery("driver", null))
        // Degenerate dims are dropped so the server falls back to PTY dims.
        assertEquals("&posture=viewer", ptyConnectQuery("viewer", 0 to 24))
    }

    @Test
    fun isPassiveOnColsMismatchOnly() {
        assertFalse(PtyPresentation.isPassive(naturalCols = 80, serverCols = 80))
        assertTrue(PtyPresentation.isPassive(naturalCols = 80, serverCols = 200))
        assertTrue(PtyPresentation.isPassive(naturalCols = 40, serverCols = 80))
        // Unknown widths never force passive.
        assertFalse(PtyPresentation.isPassive(naturalCols = 0, serverCols = 80))
        assertFalse(PtyPresentation.isPassive(naturalCols = 80, serverCols = 0))
    }

    @Test
    fun fitScaleClampsBetweenFloorAndOne() {
        // Server grid narrower than ours → letterbox at 1.
        assertEquals(1f, PtyPresentation.fitScale(naturalCols = 120, serverCols = 80))
        // Twice as wide → half scale.
        assertEquals(0.5f, PtyPresentation.fitScale(naturalCols = 40, serverCols = 80))
        // Far wider → clamped at the floor.
        assertEquals(PtyPresentation.MIN_SCALE, PtyPresentation.fitScale(naturalCols = 10, serverCols = 400))
    }

    @Test
    fun passiveFontSizeScalesAndFloors() {
        assertEquals(8f, PtyPresentation.passiveFontSize(16f, naturalCols = 100, serverCols = 200, floorPx = 6f))
        // Below the floor → clamped.
        assertEquals(6f, PtyPresentation.passiveFontSize(16f, naturalCols = 20, serverCols = 400, floorPx = 6f))
        // Narrower server grid → no upscale beyond user size is fine (ratio > 1).
        assertTrue(PtyPresentation.passiveFontSize(16f, naturalCols = 200, serverCols = 100, floorPx = 6f) >= 16f)
    }

    @Test
    fun classifierTreatsMouseAndFocusReportsAsAmbient() {
        assertTrue(PtyPresentation.isAmbientReport("$esc[<64;10;5M".toByteArray()))   // SGR wheel
        assertTrue(PtyPresentation.isAmbientReport("$esc[<0;3;4m".toByteArray()))      // SGR release
        assertTrue(PtyPresentation.isAmbientReport("$esc[I".toByteArray()))            // focus in
        assertTrue(PtyPresentation.isAmbientReport("$esc[O".toByteArray()))            // focus out
        assertTrue(PtyPresentation.isAmbientReport("$esc[M   ".toByteArray()))         // X10 mouse + 3 bytes
        // A burst of several ambient reports is still ambient.
        assertTrue(PtyPresentation.isAmbientReport("$esc[<64;1;1M$esc[<64;1;2M".toByteArray()))
    }

    @Test
    fun classifierTreatsRealInputAsNonAmbient() {
        assertFalse(PtyPresentation.isAmbientReport("a".toByteArray()))                // printable
        assertFalse(PtyPresentation.isAmbientReport("$esc[A".toByteArray()))           // up arrow (CSI)
        assertFalse(PtyPresentation.isAmbientReport("${esc}OA".toByteArray()))         // up arrow (app-cursor SS3)
        assertFalse(PtyPresentation.isAmbientReport("\r".toByteArray()))               // Enter
        assertFalse(PtyPresentation.isAmbientReport("\u0003".toByteArray()))           // Ctrl-C
        assertFalse(PtyPresentation.isAmbientReport(ByteArray(0)))                     // nothing
        // Mixed: an ambient report followed by a real keystroke is a take-over.
        assertFalse(PtyPresentation.isAmbientReport("$esc[<64;1;1Ma".toByteArray()))
    }
}
