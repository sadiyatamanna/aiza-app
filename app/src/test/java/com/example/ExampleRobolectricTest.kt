package com.example

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ui.profile.ProfileScreen
import com.example.ui.splash.SplashScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.SafetyViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("AIZA", appName)
    }

    @Test
    fun `verify Google Maps classes exist`() {
        val latLng = com.google.android.gms.maps.model.LatLng(37.422, -122.084)
        assertEquals(37.422, latLng.latitude, 0.0001)
        assertEquals(-122.084, latLng.longitude, 0.0001)
        val mapType = com.google.maps.android.compose.MapType.NORMAL
        assertEquals("NORMAL", mapType.name)
    }

    @Test
    fun `splash screen renders logo name tagline and loading indicator without developer credit in light and dark modes`() {
        val darkMode = mutableStateOf(false)
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = darkMode.value) {
                SplashScreen(onFinished = {})
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val logoDrawable = context.getDrawable(R.drawable.aegis_launcher_foreground_1784101775779)
        org.junit.Assert.assertNotNull(logoDrawable)
        org.junit.Assert.assertTrue(logoDrawable!!.intrinsicWidth > 1 && logoDrawable.intrinsicHeight > 1)
        org.junit.Assert.assertNotNull(context.getDrawable(R.drawable.ic_launcher_foreground))

        composeTestRule.onNodeWithTag("splash_logo_container").assertIsDisplayed()
        composeTestRule.onNode(androidx.compose.ui.test.hasContentDescription("AIZA Logo")).assertIsDisplayed()
        composeTestRule.onNodeWithText("AIZA").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your Safety, Our Priority").assertIsDisplayed()
        composeTestRule.onNodeWithTag("splash_loading_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithText("Developed by Tam").assertDoesNotExist()

        darkMode.value = true
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("splash_logo_container").assertIsDisplayed()
        composeTestRule.onNode(androidx.compose.ui.test.hasContentDescription("AIZA Logo")).assertIsDisplayed()
        composeTestRule.onNodeWithText("AIZA").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your Safety, Our Priority").assertIsDisplayed()
        composeTestRule.onNodeWithTag("splash_loading_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithText("Developed by Tam").assertDoesNotExist()
    }

    @Test
    fun `settings screen displays About App section with Developed by Tam in light and dark modes`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = SafetyViewModel(app)

        composeTestRule.setContent {
            val appTheme by viewModel.appTheme.collectAsState()
            MyApplicationTheme(darkTheme = appTheme == "dark") {
                ProfileScreen(
                    viewModel = viewModel,
                    innerPadding = PaddingValues(0.dp)
                )
            }
        }

        composeTestRule.onNodeWithTag("profile_list")
            .performScrollToNode(hasTestTag("about_app_section"))

        composeTestRule.onNodeWithTag("about_app_section").assertIsDisplayed()
        composeTestRule.onNodeWithText("About App").assertIsDisplayed()
        composeTestRule.onNodeWithText("AIZA").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your Safety, Our Priority").assertIsDisplayed()
        composeTestRule.onNodeWithText("Developed by Tam").assertIsDisplayed()
        composeTestRule.onNodeWithTag("logout_button").assertIsDisplayed()

        viewModel.setAppTheme("dark")
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("About App").assertIsDisplayed()
        composeTestRule.onNodeWithText("Developed by Tam").assertIsDisplayed()
    }
}
