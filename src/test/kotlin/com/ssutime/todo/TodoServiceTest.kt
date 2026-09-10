package com.ssutime.todo

import com.ssutime.auth.domain.User
import com.ssutime.auth.infrastructure.UserRepository
import com.ssutime.common.exception.UnauthorizedException
import com.ssutime.todo.application.TodoService
import com.ssutime.todo.domain.Todo
import com.ssutime.todo.domain.TodoReport
import com.ssutime.todo.domain.TodoType
import com.ssutime.todo.domain.UserTodoStatus
import com.ssutime.todo.domain.event.TodoReported
import com.ssutime.todo.infrastructure.TodoReportRepository
import com.ssutime.todo.infrastructure.TodoRepository
import com.ssutime.todo.infrastructure.UserTodoStatusRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TodoServiceTest {
    private val userRepository: UserRepository = mockk()
    private val todoRepository: TodoRepository = mockk()
    private val todoReportRepository: TodoReportRepository = mockk()
    private val userTodoStatusRepository: UserTodoStatusRepository = mockk()
    private val eventPublisher: ApplicationEventPublisher = mockk()

    private val todoService =
        TodoService(
            userRepository,
            todoRepository,
            todoReportRepository,
            userTodoStatusRepository,
            eventPublisher,
        )

    private val userId = 1L
    private val subjectId = 10L
    private val materialCode = 100001L
    private val dueDate = LocalDateTime.now().plusDays(3)
    private val title = "Test Assignment"
    private val thresholdMinutes = 60
    private val user =
        User(
            id = userId,
            authKey = "auth-1",
            maskedStudentId = "20****01",
            notificationThresholdMinutes = thresholdMinutes,
        )

    @BeforeEach
    fun setUp() {
        justRun { eventPublisher.publishEvent(any<TodoReported>()) }
        every { userRepository.findById(userId) } returns java.util.Optional.of(user)
        every {
            todoReportRepository.existsByUserIdAndSubjectIdAndMaterialCodeAndDueDateAndTitle(
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns false
    }

    @Test
    fun `processReport - 신규 todo 생성 시나리오`() {
        val report = TodoReport.create(userId, subjectId, materialCode, dueDate, title)
        val todo = Todo.create(subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)

        every { todoReportRepository.save(any()) } returns report
        every { todoRepository.findBySubjectIdAndMaterialCode(subjectId, materialCode) } returns null
        every { todoRepository.save(any()) } returns todo
        every { userTodoStatusRepository.findByUserIdAndTodo(userId, todo) } returns null
        every { userTodoStatusRepository.save(any()) } returns UserTodoStatus.create(userId, todo, thresholdMinutes)

        todoService.processReport(userId, subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)

        verify { todoReportRepository.save(any()) }
        verify { todoRepository.save(any()) }
        verify { userTodoStatusRepository.save(any()) }
        verify { eventPublisher.publishEvent(TodoReported(subjectId, materialCode, userId)) }
    }

    @Test
    fun `processReport - 기존 todo 존재 시 upsert 시나리오`() {
        val report = TodoReport.create(userId, subjectId, materialCode, dueDate, title)
        val existingTodo = Todo.create(subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)
        val existingStatus = UserTodoStatus.create(userId, existingTodo, thresholdMinutes)

        every { todoReportRepository.save(any()) } returns report
        every { todoRepository.findBySubjectIdAndMaterialCode(subjectId, materialCode) } returns existingTodo
        every { userTodoStatusRepository.findByUserIdAndTodo(userId, existingTodo) } returns existingStatus

        todoService.processReport(userId, subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)

        verify { todoReportRepository.save(any()) }
        verify(exactly = 0) { todoRepository.save(any()) }
        verify(exactly = 0) { userTodoStatusRepository.save(any()) }
        verify { eventPublisher.publishEvent(TodoReported(subjectId, materialCode, userId)) }
    }

    @Test
    fun `processReport - 기존 todo 있고 UserTodoStatus 없을 때 새로 생성`() {
        val report = TodoReport.create(userId, subjectId, materialCode, dueDate, title)
        val existingTodo = Todo.create(subjectId, materialCode, TodoType.COMMONS, dueDate, title)

        every { todoReportRepository.save(any()) } returns report
        every { todoRepository.findBySubjectIdAndMaterialCode(subjectId, materialCode) } returns existingTodo
        every { userTodoStatusRepository.findByUserIdAndTodo(userId, existingTodo) } returns null
        every { userTodoStatusRepository.save(any()) } returns UserTodoStatus.create(userId, existingTodo, thresholdMinutes)

        todoService.processReport(userId, subjectId, materialCode, TodoType.COMMONS, dueDate, title)

        verify { userTodoStatusRepository.save(any()) }
        verify { eventPublisher.publishEvent(TodoReported(subjectId, materialCode, userId)) }
    }

    @Test
    fun `processReport - TodoReported 이벤트가 올바른 파라미터로 발행됨`() {
        val report = TodoReport.create(userId, subjectId, materialCode, dueDate, title)
        val todo = Todo.create(subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)

        every { todoReportRepository.save(any()) } returns report
        every { todoRepository.findBySubjectIdAndMaterialCode(subjectId, materialCode) } returns null
        every { todoRepository.save(any()) } returns todo
        every { userTodoStatusRepository.findByUserIdAndTodo(userId, todo) } returns null
        every { userTodoStatusRepository.save(any()) } returns UserTodoStatus.create(userId, todo, thresholdMinutes)

        val eventSlot = slot<TodoReported>()
        justRun { eventPublisher.publishEvent(capture(eventSlot)) }

        todoService.processReport(userId, subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)

        val capturedEvent = eventSlot.captured
        assertEquals(subjectId, capturedEvent.subjectId)
        assertEquals(materialCode, capturedEvent.materialCode)
        assertEquals(userId, capturedEvent.userId)
    }

    @Test
    fun `processReport - 같은 사용자의 동일 report는 저장과 이벤트 발행을 건너뛴다`() {
        val existingTodo = Todo.create(subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)
        val existingStatus = UserTodoStatus.create(userId, existingTodo, thresholdMinutes)

        every {
            todoReportRepository.existsByUserIdAndSubjectIdAndMaterialCodeAndDueDateAndTitle(
                userId,
                subjectId,
                materialCode,
                dueDate,
                title,
            )
        } returns true
        every { todoRepository.findBySubjectIdAndMaterialCode(subjectId, materialCode) } returns existingTodo
        every { userTodoStatusRepository.findByUserIdAndTodo(userId, existingTodo) } returns existingStatus

        todoService.processReport(userId, subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)

        verify(exactly = 0) { todoReportRepository.save(any()) }
        verify(exactly = 0) { eventPublisher.publishEvent(any<TodoReported>()) }
    }

    @Test
    fun `UserTodoStatus notifyAt은 dueDate에서 계정 알림 시간을 뺀 시각이어야 함`() {
        val todo = Todo.create(subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)
        val status = UserTodoStatus.create(userId, todo, thresholdMinutes)

        val expectedNotifyAt = dueDate.minusMinutes(thresholdMinutes.toLong())
        assertEquals(expectedNotifyAt, status.notifyAt)
    }

    @Test
    fun `processReport - SUBMITTED 타입은 신규 todo를 과제로 생성하고 완료 처리한다`() {
        val report = TodoReport.create(userId, subjectId, materialCode, dueDate, title)
        val todoSlot = slot<Todo>()
        val statusSlot = slot<UserTodoStatus>()

        every { todoReportRepository.save(any()) } returns report
        every { todoRepository.findBySubjectIdAndMaterialCode(subjectId, materialCode) } returns null
        every { todoRepository.save(capture(todoSlot)) } answers { todoSlot.captured }
        every { userTodoStatusRepository.findByUserIdAndTodo(userId, any()) } returns null
        every { userTodoStatusRepository.save(capture(statusSlot)) } answers { statusSlot.captured }

        todoService.processReport(userId, subjectId, materialCode, TodoType.SUBMITTED, dueDate, title)

        assertEquals(TodoType.ASSIGNMENT, todoSlot.captured.type)
        assertEquals(true, statusSlot.captured.isCompleted)
        assertNotNull(statusSlot.captured.completedAt)
        verify { todoReportRepository.save(any()) }
        verify { todoRepository.save(any()) }
        verify { userTodoStatusRepository.save(any()) }
        verify { eventPublisher.publishEvent(TodoReported(subjectId, materialCode, userId)) }
    }

    @Test
    fun `processReport - SUBMITTED_LATE 타입은 기존 사용자 todo 상태를 완료 처리한다`() {
        val report = TodoReport.create(userId, subjectId, materialCode, dueDate, title)
        val todo = Todo.create(subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)
        val status = UserTodoStatus.create(userId, todo, thresholdMinutes)

        every { todoReportRepository.save(any()) } returns report
        every { todoRepository.findBySubjectIdAndMaterialCode(subjectId, materialCode) } returns todo
        every { userTodoStatusRepository.findByUserIdAndTodo(userId, todo) } returns status
        every { userTodoStatusRepository.save(status) } returns status

        todoService.processReport(userId, subjectId, materialCode, TodoType.SUBMITTED_LATE, dueDate, title)

        assertEquals(true, status.isCompleted)
        assertNotNull(status.completedAt)
        verify(exactly = 0) { todoRepository.save(any()) }
        verify { userTodoStatusRepository.save(status) }
    }

    @Test
    fun `processReport - 이미 완료된 상태에 SUBMITTED 타입이 다시 오면 완료 시각을 변경하지 않는다`() {
        val report = TodoReport.create(userId, subjectId, materialCode, dueDate, title)
        val todo = Todo.create(subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)
        val status = UserTodoStatus.create(userId, todo, thresholdMinutes)
        status.updateCompletion(completed = true)
        val completedAt = status.completedAt

        every { todoReportRepository.save(any()) } returns report
        every { todoRepository.findBySubjectIdAndMaterialCode(subjectId, materialCode) } returns todo
        every { userTodoStatusRepository.findByUserIdAndTodo(userId, todo) } returns status

        todoService.processReport(userId, subjectId, materialCode, TodoType.SUBMITTED, dueDate, title)

        assertEquals(true, status.isCompleted)
        assertEquals(completedAt, status.completedAt)
        verify(exactly = 0) { userTodoStatusRepository.save(any()) }
    }

    @Test
    fun `processReport - 제출 완료 타입이 아니면 기존 완료 상태를 미완료로 변경한다`() {
        val report = TodoReport.create(userId, subjectId, materialCode, dueDate, title)
        val todo = Todo.create(subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)
        val status = UserTodoStatus.create(userId, todo, thresholdMinutes)
        status.updateCompletion(completed = true)

        every { todoReportRepository.save(any()) } returns report
        every { todoRepository.findBySubjectIdAndMaterialCode(subjectId, materialCode) } returns todo
        every { userTodoStatusRepository.findByUserIdAndTodo(userId, todo) } returns status
        every { userTodoStatusRepository.save(status) } returns status

        todoService.processReport(userId, subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)

        assertEquals(false, status.isCompleted)
        assertEquals(null, status.completedAt)
        verify { userTodoStatusRepository.save(status) }
    }

    @Test
    fun `updateCompletion lets the owner complete a quiz`() {
        val quiz = Todo.create(subjectId, materialCode, TodoType.QUIZ, dueDate, title)
        val status = UserTodoStatus.create(userId, quiz, thresholdMinutes)
        every { userTodoStatusRepository.findById(status.id) } returns java.util.Optional.of(status)
        every { userTodoStatusRepository.save(status) } returns status

        val result = todoService.updateCompletion(userId, status.id, true)

        assertSame(status, result)
        assertTrue(result.isCompleted)
        assertNotNull(result.completedAt)
        verify { userTodoStatusRepository.save(status) }
    }

    @Test
    fun `updateCompletion rejects another users todo status`() {
        val quiz = Todo.create(subjectId, materialCode, TodoType.QUIZ, dueDate, title)
        val status = UserTodoStatus.create(userId = 2L, todo = quiz, notificationThresholdMinutes = thresholdMinutes)
        every { userTodoStatusRepository.findById(status.id) } returns java.util.Optional.of(status)

        assertFailsWith<UnauthorizedException> {
            todoService.updateCompletion(userId, status.id, true)
        }

        verify(exactly = 0) { userTodoStatusRepository.save(any()) }
    }

    @Test
    fun `manual completion is not reverted by a later incomplete LMS report`() {
        val todo = Todo.create(subjectId, materialCode, TodoType.QUIZ, dueDate, title)
        val status = UserTodoStatus.create(userId, todo, thresholdMinutes)
        status.updateCompletionManually(true)

        val changed = status.updateCompletionFromReport(false)

        assertEquals(false, changed)
        assertTrue(status.isCompleted)
        assertTrue(status.isManuallyCompleted)
    }

    @Test
    fun `processReport - 새 첨부파일 링크가 전달되면 버전을 올리지 않는 쿼리로 저장한다`() {
        val todo = stubExistingTodoReport()
        val links = listOf("https://canvas.ssu.ac.kr/courses/44383/files/1/download")
        every { todoRepository.updateAttachmentLinks(any(), any()) } returns 1

        reportWithLinks(links)

        verify { todoRepository.updateAttachmentLinks(todo.id, Todo.joinAttachmentLinks(links)) }
        verify(exactly = 0) { todoRepository.save(any()) }
    }

    @Test
    fun `processReport - 첨부파일 링크 없이 제보되면 링크를 갱신하지 않는다`() {
        stubExistingTodoReport()

        reportWithLinks(null)

        verify(exactly = 0) { todoRepository.updateAttachmentLinks(any(), any()) }
    }

    @Test
    fun `processReport - 빈 첨부파일 링크는 기존 링크를 지우지 않는다`() {
        stubExistingTodoReport().setAttachmentLinksForTest(listOf("https://canvas.ssu.ac.kr/courses/44383/files/1/download"))

        reportWithLinks(emptyList())

        verify(exactly = 0) { todoRepository.updateAttachmentLinks(any(), any()) }
    }

    @Test
    fun `processReport - 기존과 같은 첨부파일 링크면 갱신 쿼리를 생략한다`() {
        val links = listOf("https://canvas.ssu.ac.kr/courses/44383/files/1/download")
        stubExistingTodoReport().setAttachmentLinksForTest(links)

        reportWithLinks(links)

        verify(exactly = 0) { todoRepository.updateAttachmentLinks(any(), any()) }
    }

    private fun stubExistingTodoReport(): Todo {
        val todo = Todo.create(subjectId, materialCode, TodoType.ASSIGNMENT, dueDate, title)
        every { todoReportRepository.save(any()) } returns TodoReport.create(userId, subjectId, materialCode, dueDate, title)
        every { todoRepository.findBySubjectIdAndMaterialCode(subjectId, materialCode) } returns todo
        every { userTodoStatusRepository.findByUserIdAndTodo(userId, todo) } returns
            UserTodoStatus.create(userId, todo, thresholdMinutes)
        return todo
    }

    private fun reportWithLinks(attachmentLinks: List<String>?) =
        todoService.processReport(
            userId,
            subjectId,
            materialCode,
            TodoType.ASSIGNMENT,
            dueDate,
            title,
            attachmentLinks = attachmentLinks,
        )
}
