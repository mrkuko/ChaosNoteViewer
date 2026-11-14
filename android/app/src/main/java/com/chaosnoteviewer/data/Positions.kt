package com.chaosnoteviewer.data

// Priority queue order: `position` ascending, lower = higher priority. A move writes only the moved note,
// it gets a position between its new neighbours. Same algorithm as dropPosition in desktop NoteStore.js.
object Positions {
    // neighbours closer than this: renumber first
    const val MIN_GAP = 1e-9

    sealed interface Drop {
        data object Unchanged : Drop
        data object NeedsRenumber : Drop
        data class At(val position: Double) : Drop
    }

    // New position for the note shown at row [from] and dropped at row [to] (its row after the move)
    // in a list whose notes have the positions [visible].
    fun drop(visible: List<Double>, from: Int, to: Int): Drop {
        if (from == to)
            return Drop.Unchanged
        val others = visible.filterIndexed { i, _ -> i != from }
        if (to <= 0)
            return Drop.At(others.first() - 1)
        if (to >= others.size)
            return Drop.At(others.last() + 1)
        if (others[to] - others[to - 1] < MIN_GAP)
            return Drop.NeedsRenumber
        return Drop.At((others[to - 1] + others[to]) / 2)
    }
}
