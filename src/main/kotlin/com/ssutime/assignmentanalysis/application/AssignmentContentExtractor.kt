package com.ssutime.assignmentanalysis.application

import com.ssutime.assignmentanalysis.presentation.AssignmentAnalysisPayload
import com.ssutime.common.exception.InvalidRequestException
import org.springframework.stereotype.Component

@Component
class AssignmentContentExtractor(
    private val htmlParser: AssignmentHtmlParser,
    private val lmsCanvasClient: LmsCanvasClient,
    private val textExtractor: AttachmentTextExtractor,
) {
    fun extract(payload: AssignmentAnalysisPayload): ExtractedAssignmentContent {
        val parsed = htmlParser.parse(payload.assignmentHtml)
        val skipped = mutableListOf<String>()
        val attachmentTexts = mutableListOf<ExtractedAttachment>()

        processAttachments(payload, parsed, skipped, attachmentTexts)

        val allSkipped = collectSkippedFiles(skipped, attachmentTexts)

        validateAnalyzableAttachments(attachmentTexts)

        val content = buildSanitizedContent(parsed.text, attachmentTexts)
        return ExtractedAssignmentContent(
            sanitizedContent = content.take(AssignmentAnalysisLimits.MAX_EXTRACTED_CHARS),
            skippedFiles = allSkipped,
        )
    }

    fun extractAttachmentLinks(payload: AssignmentAnalysisPayload): List<String> =
        htmlParser
            .parse(payload.assignmentHtml)
            .fileLinks
            .filter { link -> validateCourse(payload, link) == null }
            .map { link -> link.downloadUrl(payload.courseId) }

    private fun processAttachments(
        payload: AssignmentAnalysisPayload,
        parsed: ParsedAssignmentHtml,
        skipped: MutableList<String>,
        attachmentTexts: MutableList<ExtractedAttachment>,
    ) {
        if (parsed.fileLinks.isEmpty()) return

        val session = createLmsSession(payload)
        var totalBytes = 0L

        parsed.fileLinks.forEach { link ->
            val result = processAttachment(payload, session, link, totalBytes)
            result.skippedFile?.let { skipped += it }
            result.attachment?.let { attachmentTexts += it }
            totalBytes += result.countedBytes
        }
    }

    private fun createLmsSession(payload: AssignmentAnalysisPayload): CanvasSession {
        val lmsSession =
            payload.lmsSession
                ?: throw InvalidRequestException("lmsSession is required when assignmentHtml has file links")
        return lmsCanvasClient.createSession(lmsSession)
    }

    private fun processAttachment(
        payload: AssignmentAnalysisPayload,
        session: CanvasSession,
        link: CanvasFileLink,
        totalBytes: Long,
    ): AttachmentProcessingResult {
        val courseValidationError = validateCourse(payload, link)
        if (courseValidationError != null) {
            return AttachmentProcessingResult(skippedFile = "${link.label}: $courseValidationError")
        }

        val metadata = lmsCanvasClient.getFileMetadata(session, payload.courseId, link.fileId)
        val validationError = validateMetadata(metadata, totalBytes)
        if (validationError != null) {
            return AttachmentProcessingResult(skippedFile = "${metadata.displayName}: $validationError")
        }

        val bytes = lmsCanvasClient.downloadFile(session, metadata)
        val downloadValidationError = validateDownload(bytes, totalBytes)
        if (downloadValidationError != null) {
            val countedBytes =
                if (bytes.size.toLong() > AssignmentAnalysisLimits.MAX_SINGLE_FILE_BYTES) 0L else bytes.size.toLong()
            return AttachmentProcessingResult(
                skippedFile = "${metadata.displayName}: $downloadValidationError",
                countedBytes = countedBytes,
            )
        }

        return AttachmentProcessingResult(
            attachment = textExtractor.extract(metadata, bytes),
            countedBytes = bytes.size.toLong(),
        )
    }

    private fun validateCourse(
        payload: AssignmentAnalysisPayload,
        link: CanvasFileLink,
    ): String? =
        if (link.courseId != null && link.courseId != payload.courseId) {
            "course mismatch"
        } else {
            null
        }

    private fun validateMetadata(
        metadata: CanvasFileMetadata,
        totalBytes: Long,
    ): String? {
        if (metadata.size > AssignmentAnalysisLimits.MAX_SINGLE_FILE_BYTES) {
            return "file too large"
        }
        if (totalBytes + metadata.size > AssignmentAnalysisLimits.MAX_TOTAL_DOWNLOAD_BYTES) {
            return "total download limit exceeded"
        }
        if (!textExtractor.supports(metadata)) {
            return "unsupported file type"
        }
        if (metadata.contentType.startsWith("image/") || metadata.contentType.startsWith("video/")) {
            return "unsupported media content type"
        }
        return null
    }

    private fun validateDownload(
        bytes: ByteArray,
        totalBytes: Long,
    ): String? {
        if (bytes.size.toLong() > AssignmentAnalysisLimits.MAX_SINGLE_FILE_BYTES) {
            return "downloaded file too large"
        }
        if (totalBytes + bytes.size > AssignmentAnalysisLimits.MAX_TOTAL_DOWNLOAD_BYTES) {
            return "total download limit exceeded"
        }
        return null
    }

    private fun collectSkippedFiles(
        skipped: List<String>,
        attachmentTexts: List<ExtractedAttachment>,
    ): List<String> =
        skipped +
            attachmentTexts.mapNotNull { attachment ->
                attachment.skippedReason?.let { "${attachment.fileName}: $it" }
            }

    private fun validateAnalyzableAttachments(attachmentTexts: List<ExtractedAttachment>) {
        if (
            attachmentTexts.none { attachment ->
                attachment.skippedReason == null && attachment.text.isNotBlank()
            }
        ) {
            throw InvalidRequestException("No analyzable attachment")
        }
    }

    private fun buildSanitizedContent(
        htmlText: String,
        attachments: List<ExtractedAttachment>,
    ): String =
        buildString {
            appendLine("[ASSIGNMENT_HTML_TEXT]")
            appendLine(htmlText)
            attachments
                .filter { it.skippedReason == null && it.text.isNotBlank() }
                .forEach { attachment ->
                    appendLine()
                    appendLine("[ATTACHMENT: ${attachment.fileName}]")
                    appendLine(attachment.text)
                }
        }

    private data class AttachmentProcessingResult(
        val attachment: ExtractedAttachment? = null,
        val skippedFile: String? = null,
        val countedBytes: Long = 0L,
    )
}
