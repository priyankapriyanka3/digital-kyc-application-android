package com.sapphirebank.kyc

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith

/**
 * Launches the real MainActivity on a device or emulator and walks the sign-in and each role workspace.
 * NOT YET RUN: it compiles (assembleDebugAndroidTest) but has not been executed on any device.
 */
@RunWith(AndroidJUnit4::class)
class AppInstrumentedSmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun has(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) {
        rule.waitUntil(timeoutMillis = 30_000) { has(text) }
    }

    private fun see(text: String) {
        waitFor(text)
        rule.onAllNodesWithText(text, substring = true)[0].assertExists()
    }

    private fun tap(text: String) {
        waitFor(text)
        rule.onAllNodesWithText(text)[0].performClick()
        rule.waitForIdle()
    }

    /** Queue cards read "Retail Loan · Meera Nair", so they are matched by substring. */
    private fun tapCard(text: String) {
        waitFor(text)
        rule.onAllNodesWithText(text, substring = true)[0].performClick()
        rule.waitForIdle()
    }

    private fun signInAs(name: String) {
        waitFor("Demo sign-in")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(name))
        rule.onNodeWithText(name).performClick()
        rule.waitForIdle()
    }

    @Test fun startsOnSignIn() {
        waitFor("Demo sign-in")
        see("Customer")
    }

    @Test fun customerSeesHomeProductsApplicationsAndProfile() {
        signInAs("Asha Rao")
        see("Welcome to")
        see("Open an Account")
        see("Our Products")
        tap("Applications")
        see("My applications")
        tap("Profile")
        see("Restrictions")
        see("Cannot see anyone else's application")
    }

    @Test fun customerWizardStartsAndValidatesStepOne() {
        signInAs("Asha Rao")
        tap("Open an Account")
        see("Choose a product")
        tap("Continue")
        // Amount is empty, so the wizard must refuse to advance and show the validation message.
        see("Enter between")
    }

    @Test fun kycAnalystSeesOnlyTheirAssignedQueue() {
        signInAs("Farhan Qureshi")
        see("Assigned Cases")
        tap("Profile")
        see("Only sees applications assigned to you")
    }

    @Test fun creditOfficerQueueAndEligibilityReview() {
        signInAs("Vikram Desai")
        see("Eligibility Review")
        tapCard("Meera Nair")
        see("Eligibility indicators")
        see("Submit Recommendation")
    }

    @Test fun branchManagerDashboardAndPipeline() {
        signInAs("Lakshmi Narayanan")
        see("Application Pipeline")
        see("Pending approval")
        tap("Cases")
        see("Cases")
        tap("Reports")
        see("Reports")
    }

    @Test fun complianceOfficerSeesFlaggedAlertsAndEvidence() {
        signInAs("Kavita Menon")
        see("AML / PEP Screening")
        see("AML/PEP alert")
        tapCard("Marta Quillon")
        see("Screening evidence")
        see("Place on Hold")
    }

    @Test fun systemAdminHasConfigurationOnlyAndNoApplicationAccess() {
        signInAs("Rahul Bose")
        see("System Configuration")
        see("User Management")
        see("KYC Rules")
        see("Product Configuration")
        see("Country Risk Settings")
        see("Reference Data")
        assertTrue("admin must not see any application list", !has("ARN:"))
        tap("Profile")
        see("Cannot view or approve individual applications")
    }

    @Test fun signingOutReturnsToSignIn() {
        signInAs("Rahul Bose")
        tap("Profile")
        tap("Sign out")
        see("Demo sign-in")
    }
}
