package cn.crid.next.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CourseTileLayoutTest {
    @Test fun shortNarrowWeekCardKeepsRoomWhileDroppingTeacher() {
        // A 64 dp card has 54 dp left after padding; the title and room both wrap.
        assertEquals(CourseTileLines(title = 2, location = 1, teacher = 0),
            courseTileLines(3, 2, 2, 14f, 13f, 54f))
    }

    @Test fun titleAndRoomUseTheirFullLinesBeforeTeacherCanAppear() {
        assertEquals(CourseTileLines(title = 2, location = 2, teacher = 0),
            courseTileLines(2, 2, 2, 14f, 13f, 70f))
        assertEquals(CourseTileLines(title = 2, location = 2, teacher = 1),
            courseTileLines(2, 2, 2, 14f, 13f, 73f))
    }

    @Test fun tallCardsKeepCompleteTeacherAfterTheEssentialFields() {
        assertEquals(CourseTileLines(title = 4, location = 3, teacher = 2),
            courseTileLines(4, 3, 2, 14f, 13f, 150f))
    }

    @Test fun aMissingRoomReturnsItsSpaceWithoutAddingAnEmptyRow() {
        assertEquals(CourseTileLines(title = 2, location = 0, teacher = 1),
            courseTileLines(2, 0, 2, 14f, 13f, 54f))
    }
}
