package cairnfire.gui

import cairnfire.Action
import cairnfire.GameReport
import cairnfire.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppStateTest {
    @Test
    fun `sem alteracoes o jogo esta sincronizado`() {
        assertEquals(GameStatus.Synced, statusOf(GameReport("Hades")))
    }

    @Test
    fun `um jogo saltado mostra o motivo`() {
        val status = statusOf(GameReport("Hades", skipped = "sem caminho"))
        assertEquals(GameStatus.Skipped("sem caminho"), status)
    }

    @Test
    fun `alteracoes pendentes ficam guardadas`() {
        val actions = listOf(Action(Kind.PUSH, "a.sav"), Action(Kind.PULL, "b.sav", conflict = true))
        val status = statusOf(GameReport("Hades", actions))
        assertIs<GameStatus.Pending>(status)
        assertEquals(actions, status.actions)
    }

    @Test
    fun `o resumo conta enviados trazidos apagados e conflitos`() {
        val actions = listOf(
            Action(Kind.PUSH, "a"), Action(Kind.PUSH, "b"),
            Action(Kind.PULL, "c", conflict = true),
            Action(Kind.DELETE_LOCAL, "d"), Action(Kind.DELETE_REMOTE, "e"),
        )
        assertEquals("2 enviados · 1 trazido · 2 apagados · 1 conflito", summary(actions))
    }

    @Test
    fun `o resumo usa o singular`() {
        assertEquals("1 enviado", summary(listOf(Action(Kind.PUSH, "a"))))
    }

    @Test
    fun `sem acoes o resumo e vazio`() {
        assertEquals("", summary(emptyList()))
    }
}
