package app.kotowski.keepsimalive.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.rules.ActivityScenarioRule
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Pins the guard behavior of safeNavigate/safePopBackStack: they only act while the current
// back stack entry's lifecycle is RESUMED, which is the only state where the destination is
// visible. The NavHost drives each visible entry's lifecycle from the activity lifecycle
// (NavController observes the host lifecycle), so the test moves the activity to CREATED to
// simulate a non-visible current entry. The graph node itself is a back stack entry in
// navigation 2.8.x, so counts below consider destination entries only.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "h1200dp")
class NavGraphTest {
    @get:Rule
    val composeTestRule: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> =
        createAndroidComposeRule()

    private lateinit var navController: NavHostController

    private fun render() {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = "a",
                ) {
                    composable("a") { Text("screen a") }
                    composable("b") { Text("screen b") }
                }
            }
        }
    }

    private fun currentState() = navController.currentBackStackEntry?.lifecycle?.currentState

    private fun screenCount(): Int = navController.currentBackStack.value.count { it.destination !is NavGraph }

    @Test
    fun `safeNavigate navigates while the current entry is resumed`() {
        render()
        assertEquals(Lifecycle.State.RESUMED, currentState())

        navController.safeNavigate("b")

        assertEquals(2, screenCount())
        assertEquals("b", navController.currentDestination?.route)
    }

    @Test
    fun `safeNavigate is ignored while the current entry is not resumed`() {
        render()
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.CREATED, currentState())

        navController.safeNavigate("b")

        assertEquals(1, screenCount())
        assertEquals("a", navController.currentDestination?.route)
    }

    @Test
    fun `safePopBackStack pops while the current entry is resumed and a previous entry exists`() {
        render()
        navController.navigate("b")
        assertEquals(2, screenCount())
        // While the enter transition is in progress the entry is clamped below RESUMED
        // (maxLifecycle), so let the transition finish before asserting the guard state.
        composeTestRule.mainClock.advanceTimeBy(1_000)
        assertEquals(Lifecycle.State.RESUMED, currentState())

        navController.safePopBackStack()

        assertEquals(1, screenCount())
        assertEquals("a", navController.currentDestination?.route)
    }

    @Test
    fun `safePopBackStack is a no-op on the start destination`() {
        render()

        navController.safePopBackStack()

        assertEquals(1, screenCount())
        assertEquals("a", navController.currentDestination?.route)
    }

    @Test
    fun `safePopBackStack is ignored while the current entry is not resumed`() {
        render()
        navController.navigate("b")
        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(Lifecycle.State.CREATED, currentState())

        navController.safePopBackStack()

        assertEquals(2, screenCount())
        assertEquals("b", navController.currentDestination?.route)
    }
}
