package com.ssutime.assignmentanalysis

import com.ssutime.assignmentanalysis.application.AssignmentAnalysisLimits
import com.ssutime.assignmentanalysis.application.AssignmentContentExtractor
import com.ssutime.assignmentanalysis.application.AssignmentHtmlParser
import com.ssutime.assignmentanalysis.application.AttachmentTextExtractor
import com.ssutime.assignmentanalysis.application.CanvasFileMetadata
import com.ssutime.assignmentanalysis.application.CanvasSession
import com.ssutime.assignmentanalysis.application.ExtractedAttachment
import com.ssutime.assignmentanalysis.application.LmsCanvasClient
import com.ssutime.assignmentanalysis.presentation.AssignmentAnalysisPayload
import com.ssutime.assignmentanalysis.presentation.LmsSessionCookieRequest
import com.ssutime.assignmentanalysis.presentation.LmsSessionRequest
import com.ssutime.common.exception.InvalidRequestException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AssignmentContentExtractorTest {
    private val lmsCanvasClient: LmsCanvasClient = mockk()
    private val extractor =
        AssignmentContentExtractor(
            htmlParser = AssignmentHtmlParser(),
            lmsCanvasClient = lmsCanvasClient,
            textExtractor = AttachmentTextExtractor(),
        )

    @Test
    fun `extract rejects assignment without attachments`() {
        val payload =
            AssignmentAnalysisPayload(
                courseId = 44383L,
                assignmentId = 10L,
                assignmentHtml = "<p>과제 설명만 있는 과제</p>",
            )

        val exception =
            assertFailsWith<InvalidRequestException> {
                extractor.extract(payload)
            }

        assertEquals("No analyzable attachment", exception.message)
        verify(exactly = 0) { lmsCanvasClient.createSession(any()) }
    }

    @Test
    fun `extractAttachmentLinks normalizes Canvas file links to absolute download URLs`() {
        val payload =
            AssignmentAnalysisPayload(
                courseId = 44383L,
                assignmentId = 10L,
                assignmentHtml =
                    """
                    <a href="https://canvas.ssu.ac.kr/courses/44383/files/4322266/download?wrap=1"
                       data-api-endpoint="https://canvas.ssu.ac.kr/api/v1/courses/44383/files/4322266">project#2-1.zip</a>
                    <a href="/files/4550358/download?wrap=1">guide.pdf</a>
                    <a href="https://canvas.ssu.ac.kr/courses/99999/files/7/download">other-course.pdf</a>
                    <a href="https://evil.example.com/files/8/download">evil</a>
                    """.trimIndent(),
            )

        val links = extractor.extractAttachmentLinks(payload)

        assertEquals(
            listOf(
                "https://canvas.ssu.ac.kr/courses/44383/files/4322266/download",
                "https://canvas.ssu.ac.kr/courses/44383/files/4550358/download",
            ),
            links,
        )
        verify(exactly = 0) { lmsCanvasClient.createSession(any()) }
    }

    @Test
    fun `extractAttachmentLinks returns empty list when assignment has no file links`() {
        val payload =
            AssignmentAnalysisPayload(
                courseId = 44383L,
                assignmentId = 10L,
                assignmentHtml = "<p>과제 설명만 있는 과제</p>",
            )

        assertEquals(emptyList(), extractor.extractAttachmentLinks(payload))
    }

    @Test
    fun `extract requires LMS session when file links exist`() {
        val payload =
            AssignmentAnalysisPayload(
                courseId = 44383L,
                assignmentId = 10L,
                assignmentHtml = "<a data-api-endpoint=\"https://canvas.ssu.ac.kr/api/v1/courses/44383/files/1\">f.txt</a>",
                lmsSession = null,
            )

        val exception = assertFailsWith<InvalidRequestException> { extractor.extract(payload) }

        assertEquals("lmsSession is required when assignmentHtml has file links", exception.message)
    }

    @Test
    fun `extract downloads allowed Canvas file and combines attachment text`() {
        val session = CanvasSession(httpClient = HttpClient.newHttpClient())
        val metadata =
            CanvasFileMetadata(
                fileId = 1L,
                displayName = "guide.txt",
                contentType = "text/plain",
                size = 12L,
                downloadUrl = "https://canvas.ssu.ac.kr/files/1/download",
            )
        every { lmsCanvasClient.createSession(validCookieSession()) } returns session
        every { lmsCanvasClient.getFileMetadata(session, 44383L, 1L) } returns metadata
        every { lmsCanvasClient.downloadFile(session, metadata) } returns "제출: report.pdf".toByteArray()

        val payload =
            AssignmentAnalysisPayload(
                courseId = 44383L,
                assignmentId = 10L,
                assignmentHtml =
                    """
                    <p>과제 설명</p>
                    <a data-api-endpoint="https://canvas.ssu.ac.kr/api/v1/courses/44383/files/1">guide.txt</a>
                    """.trimIndent(),
                lmsSession = validCookieSession(),
            )

        val extracted = extractor.extract(payload)

        assertTrue(extracted.sanitizedContent.contains("과제 설명"))
        assertTrue(extracted.sanitizedContent.contains("제출: report.pdf"))
    }

    @Test
    fun `extract rejects assignment when all attachments exceed metadata size limit`() {
        val session = CanvasSession(httpClient = HttpClient.newHttpClient())
        val metadata =
            CanvasFileMetadata(
                fileId = 1L,
                displayName = "huge.txt",
                contentType = "text/plain",
                size = AssignmentAnalysisLimits.MAX_SINGLE_FILE_BYTES + 1,
                downloadUrl = "https://canvas.ssu.ac.kr/files/1/download",
            )
        every { lmsCanvasClient.createSession(validCookieSession()) } returns session
        every { lmsCanvasClient.getFileMetadata(session, 44383L, 1L) } returns metadata

        val payload =
            AssignmentAnalysisPayload(
                courseId = 44383L,
                assignmentId = 10L,
                assignmentHtml =
                    """
                    <p>과제 설명</p>
                    <a data-api-endpoint="https://canvas.ssu.ac.kr/api/v1/courses/44383/files/1">huge.txt</a>
                    """.trimIndent(),
                lmsSession = validCookieSession(),
            )

        val exception =
            assertFailsWith<InvalidRequestException> {
                extractor.extract(payload)
            }

        assertEquals("No analyzable attachment", exception.message)
        verify(exactly = 0) { lmsCanvasClient.downloadFile(any(), any()) }
    }

    @Test
    fun `extract rejects assignment when downloaded attachment is too large`() {
        val session = CanvasSession(httpClient = HttpClient.newHttpClient())
        val metadata =
            CanvasFileMetadata(
                fileId = 1L,
                displayName = "guide.txt",
                contentType = "text/plain",
                size = 12L,
                downloadUrl = "https://canvas.ssu.ac.kr/files/1/download",
            )
        every { lmsCanvasClient.createSession(validCookieSession()) } returns session
        every { lmsCanvasClient.getFileMetadata(session, 44383L, 1L) } returns metadata
        every { lmsCanvasClient.downloadFile(session, metadata) } returns
            ByteArray(
                (AssignmentAnalysisLimits.MAX_SINGLE_FILE_BYTES + 1).toInt(),
            )

        val payload =
            AssignmentAnalysisPayload(
                courseId = 44383L,
                assignmentId = 10L,
                assignmentHtml =
                    """
                    <p>과제 설명</p>
                    <a data-api-endpoint="https://canvas.ssu.ac.kr/api/v1/courses/44383/files/1">guide.txt</a>
                    """.trimIndent(),
                lmsSession = validCookieSession(),
            )

        val exception =
            assertFailsWith<InvalidRequestException> {
                extractor.extract(payload)
            }

        assertEquals("No analyzable attachment", exception.message)
    }

    @Test
    fun `extract keeps successful attachment and reports skipped attachment`() {
        val session = CanvasSession(httpClient = HttpClient.newHttpClient())
        val unsupportedMetadata =
            CanvasFileMetadata(
                fileId = 1L,
                displayName = "image.png",
                contentType = "image/png",
                size = 12L,
                downloadUrl = "https://canvas.ssu.ac.kr/files/1/download",
            )
        val validMetadata =
            CanvasFileMetadata(
                fileId = 2L,
                displayName = "guide.txt",
                contentType = "text/plain",
                size = 12L,
                downloadUrl = "https://canvas.ssu.ac.kr/files/2/download",
            )
        every { lmsCanvasClient.createSession(validCookieSession()) } returns session
        every { lmsCanvasClient.getFileMetadata(session, 44383L, 1L) } returns unsupportedMetadata
        every { lmsCanvasClient.getFileMetadata(session, 44383L, 2L) } returns validMetadata
        every { lmsCanvasClient.downloadFile(session, validMetadata) } returns "submission guide".toByteArray()

        val payload =
            AssignmentAnalysisPayload(
                courseId = 44383L,
                assignmentId = 10L,
                assignmentHtml =
                    """
                    <p>Assignment</p>
                    <a data-api-endpoint="https://canvas.ssu.ac.kr/api/v1/courses/44383/files/1">image.png</a>
                    <a data-api-endpoint="https://canvas.ssu.ac.kr/api/v1/courses/44383/files/2">guide.txt</a>
                    """.trimIndent(),
                lmsSession = validCookieSession(),
            )

        val extracted = extractor.extract(payload)

        assertTrue(extracted.sanitizedContent.contains("submission guide"))
        assertEquals(listOf("image.png: unsupported file type"), extracted.skippedFiles)
        verify(exactly = 0) { lmsCanvasClient.downloadFile(session, unsupportedMetadata) }
    }

    @Test
    fun `extract applies total download limit across attachments`() {
        val textExtractor: AttachmentTextExtractor = mockk()
        val extractor =
            AssignmentContentExtractor(
                htmlParser = AssignmentHtmlParser(),
                lmsCanvasClient = lmsCanvasClient,
                textExtractor = textExtractor,
            )
        val session = CanvasSession(httpClient = HttpClient.newHttpClient())
        val metadata =
            (1L..4L).associateWith { fileId ->
                CanvasFileMetadata(
                    fileId = fileId,
                    displayName = "$fileId.txt",
                    contentType = "text/plain",
                    size = if (fileId == 4L) 1L else AssignmentAnalysisLimits.MAX_SINGLE_FILE_BYTES,
                    downloadUrl = "https://canvas.ssu.ac.kr/files/$fileId/download",
                )
            }
        every { textExtractor.supports(any()) } returns true
        every { textExtractor.extract(any(), any()) } answers {
            val fileMetadata = firstArg<CanvasFileMetadata>()
            ExtractedAttachment(fileMetadata.displayName, "content")
        }
        every { lmsCanvasClient.createSession(validCookieSession()) } returns session
        metadata.forEach { (fileId, fileMetadata) ->
            every { lmsCanvasClient.getFileMetadata(session, 44383L, fileId) } returns fileMetadata
            if (fileId != 4L) {
                every { lmsCanvasClient.downloadFile(session, fileMetadata) } returns
                    ByteArray(AssignmentAnalysisLimits.MAX_SINGLE_FILE_BYTES.toInt())
            }
        }

        val links =
            (1L..4L).joinToString("\n") { fileId ->
                "<a data-api-endpoint=\"https://canvas.ssu.ac.kr/api/v1/courses/44383/files/$fileId\">$fileId.txt</a>"
            }
        val payload =
            AssignmentAnalysisPayload(
                courseId = 44383L,
                assignmentId = 10L,
                assignmentHtml = links,
                lmsSession = validCookieSession(),
            )

        val extracted = extractor.extract(payload)

        assertEquals(listOf("4.txt: total download limit exceeded"), extracted.skippedFiles)
        verify(exactly = 0) { lmsCanvasClient.downloadFile(session, metadata.getValue(4L)) }
    }

    private fun validCookieSession(): LmsSessionRequest =
        LmsSessionRequest(
            cookies =
                listOf(
                    LmsSessionCookieRequest(
                        name = "_normandy_session",
                        value = "session",
                        domain = "canvas.ssu.ac.kr",
                    ),
                ),
        )
}
