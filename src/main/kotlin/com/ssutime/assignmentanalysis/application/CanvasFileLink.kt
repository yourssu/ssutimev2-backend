package com.ssutime.assignmentanalysis.application

data class CanvasFileLink(
    val courseId: Long?,
    val fileId: Long,
    val sourceUrl: String,
    val label: String,
) {
    fun downloadUrl(fallbackCourseId: Long): String =
        "https://${AssignmentHtmlParser.CANVAS_HOST}/courses/${courseId ?: fallbackCourseId}/files/$fileId/download"
}
