package cairnfire.gui

import cairnfire.Action
import cairnfire.GameReport
import cairnfire.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppStateTest {
    @Test
    fun `with no changes the game is in sync`() {
        assertEquals(GameStatus.Synced, statusOf(GameReport("Hades")))
    }

    @Test
    fun `a skipped game shows the reason`() {
        val status = statusOf(GameReport("Hades", skipped = "no path"))
        assertEquals(GameStatus.Skipped("no path"), status)
    }

    @Test
    fun `pending changes are kept`() {
        val actions = listOf(Action(Kind.PUSH, "a.sav"), Action(Kind.PULL, "b.sav", conflict = true))
        val status = statusOf(GameReport("Hades", actions))
        assertIs<GameStatus.Pending>(status)
        assertEquals(actions, status.actions)
    }

    @Test
    fun `the summary counts pushed, pulled, deleted and conflicts`() {
        val actions = listOf(
            Action(Kind.PUSH, "a"), Action(Kind.PUSH, "b"),
            Action(Kind.PULL, "c", conflict = true),
            Action(Kind.DELETE_LOCAL, "d"), Action(Kind.DELETE_REMOTE, "e"),
        )
        assertEquals("2 pushed · 1 pulled · 2 deleted · 1 conflict", summary(actions))
    }

    @Test
    fun `the summary uses the singular`() {
        assertEquals("1 pushed", summary(listOf(Action(Kind.PUSH, "a"))))
    }

    @Test
    fun `with no actions the summary is empty`() {
        assertEquals("", summary(emptyList()))
    }
}
