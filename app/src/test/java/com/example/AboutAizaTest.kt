package com.example

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ui.profile.AboutAizaScreen
import com.example.ui.profile.ProfileScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SafetyViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AboutAizaTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var viewModel: SafetyViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        viewModel = SafetyViewModel(application)
    }

    @Test
    fun `settings screen displays About row and subtitle`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                ProfileScreen(
                    viewModel = viewModel,
                    innerPadding = PaddingValues(0.dp)
                )
            }
        }

        composeTestRule.onNodeWithTag("profile_list")
            .performScrollToNode(hasTestTag("settings_row_about"))

        composeTestRule.onNodeWithTag("settings_row_about").assertIsDisplayed()
        composeTestRule.onNodeWithText("About").assertIsDisplayed()
        composeTestRule.onNodeWithText("Learn about AIZA, its technology and safety features").assertIsDisplayed()
    }

    @Test
    fun `settings screen confirms People Who Saved Me row is removed`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                ProfileScreen(
                    viewModel = viewModel,
                    innerPadding = PaddingValues(0.dp)
                )
            }
        }

        // Verify that the removed row does NOT exist anywhere in the profile/settings list
        composeTestRule.onNodeWithTag("settings_row_people_who_saved_me").assertDoesNotExist()
        composeTestRule.onNodeWithText("People Who Saved Me").assertDoesNotExist()
    }

    @Test
    fun `tapping About row navigates to About AIZA screen and back button returns`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                ProfileScreen(
                    viewModel = viewModel,
                    innerPadding = PaddingValues(0.dp)
                )
            }
        }

        composeTestRule.onNodeWithTag("profile_list")
            .performScrollToNode(hasTestTag("settings_row_about"))

        composeTestRule.onNodeWithTag("settings_row_about").performClick()
        composeTestRule.waitForIdle()

        // Verify About AIZA screen is displayed
        composeTestRule.onNodeWithTag("about_aiza_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("about_title_topbar").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your Safety, Our Priority").assertIsDisplayed()
        composeTestRule.onNodeWithText("Developed by Tam").assertIsDisplayed()

        // Tap back
        composeTestRule.onNodeWithTag("about_back_button").performClick()
        composeTestRule.waitForIdle()

        // Verify back in profile list
        composeTestRule.onNodeWithTag("profile_list").assertIsDisplayed()
    }

    @Test
    fun `settings screen does not contain People Who Saved Me navigation`() {
        composeTestRule.setContent {
            MyApplicationTheme {
                ProfileScreen(
                    viewModel = viewModel,
                    innerPadding = PaddingValues(0.dp)
                )
            }
        }

        composeTestRule.onNodeWithTag("settings_row_people_who_saved_me").assertDoesNotExist()
        composeTestRule.onNodeWithTag("people_who_saved_me_screen").assertDoesNotExist()
    }

    @Test
    fun `AboutAizaScreen displays full content sections and supports dark theme`() {
        var backCalled = false

        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                AboutAizaScreen(
                    innerPadding = PaddingValues(0.dp),
                    onBack = { backCalled = true }
                )
            }
        }

        // Header
        composeTestRule.onNodeWithTag("about_header_section").assertIsDisplayed()
        composeTestRule.onNodeWithTag("about_logo_container").assertIsDisplayed()
        composeTestRule.onNodeWithText("AIZA").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your Safety, Our Priority").assertIsDisplayed()
        composeTestRule.onNodeWithText("Developed by Tam").assertIsDisplayed()

        // About the app description
        composeTestRule.onNodeWithTag("about_description_card").assertIsDisplayed()

        // Safety features
        composeTestRule.onNodeWithTag("about_scrollable_content")
            .performScrollToNode(hasTestTag("about_safety_features_section"))
        composeTestRule.onNodeWithTag("about_safety_features_section").assertIsDisplayed()
        composeTestRule.onNodeWithText("Manual SOS").assertExists()
        composeTestRule.onNodeWithText("Shake SOS").assertExists()
        composeTestRule.onNodeWithText("Voice SOS").assertExists()
        composeTestRule.onNodeWithText("Bluetooth SOS").assertExists()
        composeTestRule.onNodeWithText("Guardian Communication").assertExists()
        composeTestRule.onNodeWithText("Emergency Location").assertExists()
        composeTestRule.onNodeWithText("Normal Location Sharing").assertExists()
        composeTestRule.onNodeWithText("Emergency History").assertExists()
        composeTestRule.onNodeWithText("Emergency Notifications").assertExists()
        composeTestRule.onNodeWithText("People Who Saved Me").assertDoesNotExist()

        // Security & Privacy
        composeTestRule.onNodeWithTag("about_scrollable_content")
            .performScrollToNode(hasTestTag("about_security_privacy_card"))
        composeTestRule.onNodeWithTag("about_security_privacy_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Security & Privacy").assertExists()

        // Post-Quantum Security
        composeTestRule.onNodeWithTag("about_scrollable_content")
            .performScrollToNode(hasTestTag("about_post_quantum_card"))
        composeTestRule.onNodeWithTag("about_post_quantum_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Post-Quantum Security").assertExists()

        // Technology
        composeTestRule.onNodeWithTag("about_scrollable_content")
            .performScrollToNode(hasTestTag("about_technology_card"))
        composeTestRule.onNodeWithTag("about_technology_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Technology").assertExists()
        composeTestRule.onNodeWithText("Kotlin").assertExists()
        composeTestRule.onNodeWithText("Jetpack Compose").assertExists()
        composeTestRule.onNodeWithText("Bouncy Castle").assertExists()
        composeTestRule.onNodeWithText("ML-KEM").assertExists()
        composeTestRule.onNodeWithText("ML-DSA").assertExists()
        composeTestRule.onNodeWithText("AES-256-GCM").assertExists()

        // Emergency Communication
        composeTestRule.onNodeWithTag("about_scrollable_content")
            .performScrollToNode(hasTestTag("about_emergency_communication_card"))
        composeTestRule.onNodeWithTag("about_emergency_communication_card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Emergency Communication").assertExists()

        // Important Note
        composeTestRule.onNodeWithTag("about_scrollable_content")
            .performScrollToNode(hasTestTag("about_important_note_card"))
        composeTestRule.onNodeWithTag("about_important_note_card").assertIsDisplayed()

        // App Information
        composeTestRule.onNodeWithTag("about_scrollable_content")
            .performScrollToNode(hasTestTag("about_app_information_card"))
        composeTestRule.onNodeWithTag("about_app_information_card").assertIsDisplayed()

        // Test back button
        composeTestRule.onNodeWithTag("about_back_button").performClick()
        assert(backCalled)
    }
}
