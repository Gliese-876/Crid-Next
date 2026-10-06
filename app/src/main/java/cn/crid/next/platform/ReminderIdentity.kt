package cn.crid.next.platform

import cn.crid.next.core.AppState
import cn.crid.next.core.Occurrence
import java.security.MessageDigest

internal object ReminderIdentity {
    /** Formatting-only edits do not create another notification for an already delivered class. */
    fun deliveryId(state: AppState, occurrence: Occurrence): String {
        val fields = listOf(state.selectedSemesterId.orEmpty(), state.selectedPlanId.orEmpty(),
            occurrence.course.id, occurrence.date.toString(), occurrence.start.toString(), occurrence.end.toString(),
            occurrence.lesson.location.filterNot(Char::isWhitespace), occurrence.lesson.teacher.filterNot(Char::isWhitespace))
        val bytes = fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        // The local class date/time stays stable if the phone's time zone changes after delivery.
        return "${occurrence.date}:$hash"
    }
}
