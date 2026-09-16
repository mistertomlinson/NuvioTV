package com.nuvio.tv.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        val targetPackage = "com.nuvio.tv.baseline"

        rule.collect(
            packageName = targetPackage,
            includeInStartupProfile = true
        ) {
            fun clickIfVisible(
                text: String,
                timeoutMs: Long = 1_500L
            ): Boolean {
                val target =
                    device.wait(
                        Until.findObject(By.text(text)),
                        timeoutMs
                    ) ?: return false

                target.click()
                device.waitForIdle()
                Thread.sleep(1_000L)
                return true
            }

            pressHome()
            startActivityAndWait()

            device.wait(
                Until.hasObject(By.pkg(targetPackage)),
                10_000L
            )

            // A profiling package is a fresh installation. Dismiss its update
            // prompt and select the same Home experience used by Enhanced.
            clickIfVisible("Ignore", timeoutMs = 5_000L)

            if (!clickIfVisible("Modern Home", timeoutMs = 3_000L)) {
                clickIfVisible("Modern", timeoutMs = 1_500L)
            }

            // Support any remaining first-run account flow.
            clickIfVisible("Continue without account")
            clickIfVisible("Advanced")
            clickIfVisible("Continue")

            // Nuvio presents its profile chooser on every cold launch.
            clickIfVisible("Brandon", timeoutMs = 5_000L)

            device.waitForIdle()
            Thread.sleep(5_000L)

            // Exercise horizontal Home-card composition and focus movement.
            repeat(6) {
                device.pressDPadRight()
                Thread.sleep(350L)
            }

            // Exercise row creation, disposal, focus restoration, images,
            // metadata badges and the customized Home enrichment path.
            repeat(8) {
                device.pressDPadDown()
                Thread.sleep(500L)
            }

            repeat(2) {
                device.pressDPadUp()
                Thread.sleep(400L)
            }

            repeat(4) {
                device.pressDPadRight()
                Thread.sleep(350L)
            }

            // Cover the Home-to-Details path and its initial composition.
            device.pressDPadCenter()
            device.waitForIdle()
            Thread.sleep(3_500L)

            device.pressBack()
            device.waitForIdle()
            Thread.sleep(2_000L)

            // Revisit already-created rows to cover the warm navigation path.
            repeat(4) {
                device.pressDPadLeft()
                Thread.sleep(300L)
            }

            repeat(3) {
                device.pressDPadUp()
                Thread.sleep(350L)
            }
        }
    }
}
