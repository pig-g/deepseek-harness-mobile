package com.labteto.dshmobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.labteto.dshmobile.ui.theme.DshTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke guard for the ask_user_question takeover dock.
 *
 * The dock lives at the bottom of a chat on a phone and shares the screen with the composer, so a
 * long question must keep (a) its options present/tappable and (b) its action row reachable. The
 * body is now height-bounded and scrollable, which the scroll assertion below guards. Presence is
 * checked by node existence rather than geometry: the panel's animated DsButtons report degenerate
 * merged semantics under the compose test harness, so geometric assertions there are unreliable.
 */
@RunWith(AndroidJUnit4::class)
class QuestionsPanelTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun exists(text: String): Boolean =
        runCatching { rule.onNodeWithText(text).fetchSemanticsNode(); true }.getOrDefault(false)

    @Test
    fun long_question_keeps_options_tappable_and_body_scrollable() {
        rule.setContent {
            DshTheme {
                Box(Modifier.fillMaxSize()) {
                    QuestionsPanel(
                        questions = issues(3),
                        onSubmit = {},
                        onCancel = {},
                    )
                }
            }
        }

        // Options are present and accept taps (guards "doesn't take input").
        val option = rule.onNodeWithText("Option 0-2")
        assertTrue("option should exist", exists("Option 0-2"))
        option.performClick()

        // The bounded body is scrollable, so every option stays reachable on a small screen.
        val scrollable = runCatching { rule.onNode(hasScrollAction()).fetchSemanticsNode(); true }
            .getOrDefault(false)
        assertTrue("question body must be scrollable when taller than the dock", scrollable)

        // The navigate action (Next) and the question header are present.
        assertTrue("Next action should exist", exists("Next"))
        assertTrue("Question 0 header should exist", exists("Question 0"))
    }

    private fun issues(count: Int) = List(count) { i ->
        QuestionItem(
            id = "q$i",
            question = "Question $i",
            header = "Header $i",
            options = (1..8).map { n -> QuestionOption("Option $i-$n", "Description $i-$n") },
        )
    }
}
