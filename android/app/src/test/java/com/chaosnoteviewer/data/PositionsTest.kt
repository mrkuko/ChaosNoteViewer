package com.chaosnoteviewer.data

import com.chaosnoteviewer.data.Positions.Drop
import org.junit.Assert.assertEquals
import org.junit.Test

class PositionsTest {
    private val list = listOf(1.0, 2.0, 3.0, 4.0)

    @Test fun sameRowChangesNothing() = assertEquals(Drop.Unchanged, Positions.drop(list, 2, 2))

    @Test fun toTopGoesBeforeFirst() = assertEquals(Drop.At(0.0), Positions.drop(list, 2, 0))

    @Test fun toBottomGoesAfterLast() = assertEquals(Drop.At(5.0), Positions.drop(list, 0, 3))

    // row 3 (4.0) dropped at row 1: between 1.0 and 2.0
    @Test fun upBetweenNeighbours() = assertEquals(Drop.At(1.5), Positions.drop(list, 3, 1))

    // row 0 (1.0) dropped at row 2: others are 2,3,4 -> between 3.0 and 4.0
    @Test fun downBetweenNeighbours() = assertEquals(Drop.At(3.5), Positions.drop(list, 0, 2))

    @Test fun tooCloseNeedsRenumber() =
        assertEquals(Drop.NeedsRenumber, Positions.drop(listOf(1.0, 1.0 + 1e-12, 3.0), 2, 1))
}
