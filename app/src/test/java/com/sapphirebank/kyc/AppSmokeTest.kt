package com.sapphirebank.kyc

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Launches the real MainActivity on the JVM (Robolectric) and walks the three screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = KycApp::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun tap(text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun tab(text: String) {
        rule.onNodeWithText(text).performClick()
        rule.waitForIdle()
    }

    private fun see(text: String) =
        rule.onAllNodesWithText(text, substring = true)[0].performScrollTo().assertIsDisplayed()

    @Test fun launchesAndShowsAllThreeTabs() {
        rule.onNodeWithText("Sapphire Bank · Digital KYC").assertIsDisplayed()
        rule.onNodeWithText("Submit application").assertExists()
        tab("Tracker"); rule.onNodeWithText("No application yet").assertIsDisplayed()
        tab("Review"); rule.onNodeWithText("Nothing to review").assertIsDisplayed()
    }

    @Test fun invalidFormShowsRuleErrors() {
        tap("Submit application")
        see("Enter PAN or tick Form 60")
    }

    @Test fun fullJourneyThroughDualControl() {
        // Screen 1
        tap("Fill sample")
        tap("Submit application")
        see("Application ARN-000001")
        see("XXXX XXXX 0124")            // DOC-003 Aadhaar masking

        // Screen 2
        tab("Tracker")
        see("Application stages")
        tap("Start KYC (Stage 3)")
        tap("Search CKYCR (KYC-005)")
        // liveness defaults to false: first record fails KYC-006 evaluation, then fix and re-record
        tap("Record V-CIP session")
        see("KYC-006")
        tap("Liveness check passed")
        tap("Record V-CIP session")
        tap("Complete KYC")
        repeat(8) {
            if (rule.onAllNodesWithText("Advance from", substring = true).fetchSemanticsNodes().isNotEmpty()) {
                rule.onNodeWithText("Advance from", substring = true).performScrollTo().performClick()
                rule.waitForIdle()
            }
        }
        see("Waiting for dual-control sign-off")

        // Screen 3
        tab("Review")
        see("Dual-control sign-off")
        rule.onAllNodesWithText("Approve")[0].performScrollTo().performClick()
        rule.waitForIdle()
        see("L1 Credit Officer")
        rule.onAllNodesWithText("Approve")[0].performScrollTo().performClick()
        rule.waitForIdle()
        see("Open account (Stage 7)")
        tap("Verify chain")
        see("Chain intact")
        tap("Simulate tamper")
        tap("Verify chain")
        see("Hash mismatch at #1")
    }
}
