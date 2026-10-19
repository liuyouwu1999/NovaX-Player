package com.novax.app.ui.glass

import androidx.compose.ui.graphics.Color
import com.novax.app.ui.theme.DarkPalette
import com.novax.app.ui.theme.LightPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTokenTest {
    @Test fun lightAndDarkDifferAndNonZero() {
        assertTrue(DarkPalette.GlassFill != Color.Unspecified && LightPalette.GlassFill != Color.Unspecified)
        assertTrue(DarkPalette.GlassFill != LightPalette.GlassFill)
        assertTrue(DarkPalette.GlassFillStrong.alpha > DarkPalette.GlassFill.alpha)   // SCRIM 档更实
        assertTrue(LightPalette.GlassFillStrong.alpha > LightPalette.GlassFill.alpha)
    }
    @Test fun scrimReadabilityDirection() {
        // 可读性红线（spec §8）：暗色玻璃偏黑（红分量低）、亮色玻璃偏白（红分量高）
        assertTrue(DarkPalette.GlassFillStrong.red < 0.25f)
        assertTrue(LightPalette.GlassFillStrong.red > 0.85f)
    }
}
