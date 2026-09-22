package app.kotowski.keepsimalive.ui.simsettings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import app.kotowski.keepsimalive.data.FrequencyType
import app.kotowski.keepsimalive.data.SimKeepaliveConfig
import app.kotowski.keepsimalive.ui.theme.KeepSimAliveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ConfigEditorValidationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    private fun renderEditor(initialMessage: String = "hi") {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                KeepaliveConfigEditor(
                    context = context,
                    simId = 1,
                    initial =
                        SimKeepaliveConfig(
                            simId = 1,
                            enabled = true,
                            message = initialMessage,
                            freqType = FrequencyType.MONTHLY,
                        ),
                    onConfigChanged = {},
                    onValidationChanged = { _, _ -> },
                )
            }
        }
    }

    @Test
    fun shortInvalidRecipientShowsNoInlineError() {
        renderEditor()
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("+123456")
        composeTestRule
            .onNodeWithText("Enter a valid phone number with country code", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun validRecipientShowsNoError() {
        renderEditor()
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("+5550100")
        composeTestRule
            .onNodeWithText("Enter a valid phone number with country code", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun messageAndScheduleCardsShownWhileKeepaliveDisabled() {
        composeTestRule.setContent {
            KeepSimAliveTheme {
                KeepaliveConfigEditor(
                    context = context,
                    simId = 1,
                    initial =
                        SimKeepaliveConfig(
                            simId = 1,
                            enabled = false,
                            message = "hi",
                            freqType = FrequencyType.MONTHLY,
                        ),
                    onConfigChanged = {},
                    onValidationChanged = { _, _ -> },
                )
            }
        }
        composeTestRule.onNodeWithText("Message details", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Schedule rules", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun recipientFieldShowsPlusPrefixAndPhoneLabelWithoutPlaceholder() {
        renderEditor()
        composeTestRule.onNodeWithText("Recipient", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("+15550101", useUnmergedTree = true).assertDoesNotExist()
        // The "+" prefix appears when the field has content (M3 hides prefix on empty value).
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("5550100")
        composeTestRule.onNodeWithText("+", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun recipientDigitsAreStoredWithPlusPrefix() {
        var pending: SimKeepaliveConfig? = null
        composeTestRule.setContent {
            KeepSimAliveTheme {
                KeepaliveConfigEditor(
                    context = context,
                    simId = 1,
                    initial =
                        SimKeepaliveConfig(
                            simId = 1,
                            enabled = true,
                            message = "hi",
                            freqType = FrequencyType.MONTHLY,
                        ),
                    onConfigChanged = { pending = it },
                    onValidationChanged = { _, _ -> },
                )
            }
        }
        // The user types digits only; the stored E.164 number carries the fixed "+" prefix.
        composeTestRule.onAllNodes(hasSetTextAction())[0].performTextInput("5550100")
        assertEquals("+5550100", pending?.recipientPhone)
    }

    @Test
    fun emptyRecipientShowsNoError() {
        renderEditor()
        composeTestRule
            .onNodeWithText("Enter a valid phone number with country code", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun clearingRecipientShowsNoError() {
        renderEditor()
        val field = composeTestRule.onAllNodes(hasSetTextAction())[0]
        field.performTextInput("5550100")
        // Clear the field by invoking the SetText semantics action directly.
        val setText = field.fetchSemanticsNode().config.get(SemanticsActions.SetText)
        setText?.action?.invoke(AnnotatedString(""))
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText("Enter a valid phone number with country code", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun emptyMessageShowsNoInlineError() {
        renderEditor(initialMessage = "hi")
        val messageField = composeTestRule.onAllNodes(hasSetTextAction())[1]
        // Empty the field by invoking the SetText semantics action directly.
        val setText = messageField.fetchSemanticsNode().config.get(SemanticsActions.SetText)
        setText?.action?.invoke(AnnotatedString(""))
        composeTestRule.waitForIdle()
        composeTestRule
            .onNodeWithText("Message is required", useUnmergedTree = true)
            .assertDoesNotExist()
    }
}
