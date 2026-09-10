package com.ssutime.todo

import com.ssutime.assignmentanalysis.application.AssignmentAnalysisPreparationService
import com.ssutime.assignmentanalysis.domain.AssignmentAnalysisStatus
import com.ssutime.assignmentanalysis.presentation.AssignmentAnalysisPayload
import com.ssutime.assignmentanalysis.presentation.AssignmentAnalysisResponse
import com.ssutime.assignmentanalysis.presentation.TodoReportWithAnalysisRequest
import com.ssutime.common.exception.InvalidRequestException
import com.ssutime.todo.application.TodoService
import com.ssutime.todo.domain.Todo
import com.ssutime.todo.domain.TodoType
import com.ssutime.todo.presentation.TodoController
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertFailsWith

class TodoControllerTest {
    private val todoService: TodoService = mockk()
    private val preparationService: AssignmentAnalysisPreparationService = mockk()
    private val controller = TodoController(todoService, preparationService)

    private val userId = 1L
    private val dueDate = LocalDateTime.of(2026, 9, 20, 23, 59)
    private val payload = AssignmentAnalysisPayload(44383L, 718158L, "<a href=\"/courses/44383/files/1/download\">f.pdf</a>")
    private val request = TodoReportWithAnalysisRequest(10L, 20L, TodoType.ASSIGNMENT, dueDate, "실습과제 3", payload)
    private val todo = Todo.create(10L, 20L, TodoType.ASSIGNMENT, dueDate, "실습과제 3")
    private val links = listOf("https://canvas.ssu.ac.kr/courses/44383/files/1/download")

    @Test
    fun `reportWithAnalysis saves extracted attachment links with the report before preparing analysis`() {
        every { preparationService.extractAttachmentLinks(payload) } returns links
        every { todoService.processReport(any(), any(), any(), any(), any(), any(), any()) } returns todo
        every { preparationService.prepareAnalysis(todo, payload) } returns
            AssignmentAnalysisResponse(1L, AssignmentAnalysisStatus.PENDING, emptyList())

        controller.reportWithAnalysis(userId, request)

        verifyOrder {
            preparationService.extractAttachmentLinks(payload)
            todoService.processReport(userId, 10L, 20L, TodoType.ASSIGNMENT, dueDate, "실습과제 3", links)
            preparationService.prepareAnalysis(todo, payload)
        }
    }

    @Test
    fun `reportWithAnalysis keeps attachment links in the report even when analysis preparation fails`() {
        every { preparationService.extractAttachmentLinks(payload) } returns links
        every { todoService.processReport(any(), any(), any(), any(), any(), any(), any()) } returns todo
        every { preparationService.prepareAnalysis(todo, payload) } throws InvalidRequestException("No analyzable attachment")

        assertFailsWith<InvalidRequestException> { controller.reportWithAnalysis(userId, request) }

        verify { todoService.processReport(userId, 10L, 20L, TodoType.ASSIGNMENT, dueDate, "실습과제 3", links) }
    }
}
