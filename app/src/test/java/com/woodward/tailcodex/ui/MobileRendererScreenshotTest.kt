package com.woodward.tailcodex.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

class SmallPhoneRendererScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.NEXUS_5.copy(softButtons = false),
        theme = "android:style/Theme.Material.Light.NoActionBar",
    )

    @Test fun longMarkdownAndFormulaPortrait() = snapshot(RendererScenario.MARKDOWN_MATH)
    @Test fun longCodeAndCollapsedCommandOutputPortrait() = snapshot(RendererScenario.CODE_COMMAND)
    @Test fun largeUnifiedDiffPortrait() = snapshot(RendererScenario.DIFF)
    @Test fun approvalDialogPortrait() = snapshot(RendererScenario.APPROVAL)
    @Test fun runningComposerLayoutPortrait() = snapshot(RendererScenario.COMPOSER)

    private fun snapshot(scenario: RendererScenario) {
        paparazzi.snapshot {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MobileRendererShowcase(scenario)
            }
        }
    }
}

class LargeFontRendererScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(fontScale = 1.5f, softButtons = false),
        theme = "android:style/Theme.Material.Light.NoActionBar",
    )

    @Test fun markdownMathLargePhoneLargeFont() {
        paparazzi.snapshot {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MobileRendererShowcase(RendererScenario.MARKDOWN_MATH)
            }
        }
    }

    @Test fun composerLargePhoneLargeFont() {
        paparazzi.snapshot {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MobileRendererShowcase(RendererScenario.COMPOSER)
            }
        }
    }
}

class LandscapeDarkRendererScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.NEXUS_5.copy(
            orientation = ScreenOrientation.LANDSCAPE,
            nightMode = NightMode.NIGHT,
            softButtons = false,
        ),
        theme = "android:style/Theme.Material.NoActionBar",
    )

    @Test fun codeAndCommandLandscapeDark() {
        paparazzi.snapshot {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MobileRendererShowcase(RendererScenario.CODE_COMMAND, darkTheme = true)
            }
        }
    }

    @Test fun diffLandscapeDark() {
        paparazzi.snapshot {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                MobileRendererShowcase(RendererScenario.DIFF, darkTheme = true)
            }
        }
    }
}
