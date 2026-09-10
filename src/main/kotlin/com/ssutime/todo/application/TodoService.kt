package com.ssutime.todo.application

import com.ssutime.auth.infrastructure.UserRepository
import com.ssutime.common.exception.ResourceNotFoundException
import com.ssutime.common.exception.UnauthorizedException
import com.ssutime.todo.domain.Todo
import com.ssutime.todo.domain.TodoReport
import com.ssutime.todo.domain.TodoType
import com.ssutime.todo.domain.UserTodoStatus
import com.ssutime.todo.domain.event.TodoReported
import com.ssutime.todo.infrastructure.TodoReportRepository
import com.ssutime.todo.infrastructure.TodoRepository
import com.ssutime.todo.infrastructure.UserTodoStatusRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional
class TodoService(
    private val userRepository: UserRepository,
    private val todoRepository: TodoRepository,
    private val todoReportRepository: TodoReportRepository,
    private val userTodoStatusRepository: UserTodoStatusRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
) {
    fun processReport(
        userId: Long,
        subjectId: Long,
        materialCode: Long,
        type: TodoType,
        dueDate: LocalDateTime,
        title: String,
        attachmentLinks: List<String>? = null,
    ): Todo {
        val user =
            userRepository
                .findById(userId)
                .orElseThrow { ResourceNotFoundException("User not found: $userId") }

        val savedNewReport =
            if (todoReportRepository.existsByUserIdAndSubjectIdAndMaterialCodeAndDueDateAndTitle(
                    userId = userId,
                    subjectId = subjectId,
                    materialCode = materialCode,
                    dueDate = dueDate,
                    title = title,
                )
            ) {
                false
            } else {
                TodoReport
                    .create(userId, subjectId, materialCode, dueDate, title)
                    .let { todoReportRepository.save(it) }
                true
            }

        val completed = type.isCompletedAssignment()
        val todoType = type.toTodoType()

        val todo =
            todoRepository.findBySubjectIdAndMaterialCode(subjectId, materialCode)
                ?: todoRepository.save(Todo.create(subjectId, materialCode, todoType, dueDate, title))
        attachmentLinks
            ?.takeIf { it.isNotEmpty() && it != todo.attachmentLinks }
            ?.let { todoRepository.updateAttachmentLinks(todo.id, Todo.joinAttachmentLinks(it)) }

        val existingStatus = userTodoStatusRepository.findByUserIdAndTodo(userId, todo)
        val status =
            existingStatus
                ?.apply { recalculateNotifyAt(user.notificationThresholdMinutes) }
                ?: UserTodoStatus.create(
                    userId = userId,
                    todo = todo,
                    notificationThresholdMinutes = user.notificationThresholdMinutes,
                )

        if (status.updateCompletionFromReport(completed) || existingStatus == null) {
            userTodoStatusRepository.save(status)
        }

        if (savedNewReport) {
            applicationEventPublisher.publishEvent(TodoReported(subjectId, materialCode, userId))
        }
        return todo
    }

    @Transactional(readOnly = true)
    fun getUserTodoStatuses(userId: Long): List<UserTodoStatus> = userTodoStatusRepository.findAllByUserId(userId)

    fun updateCompletion(
        userId: Long,
        userTodoStatusId: Long,
        isCompleted: Boolean,
    ): UserTodoStatus {
        val status =
            userTodoStatusRepository
                .findById(userTodoStatusId)
                .orElseThrow { ResourceNotFoundException("UserTodoStatus not found: $userTodoStatusId") }
        if (status.userId != userId) {
            throw UnauthorizedException("Not your todo status")
        }
        if (status.updateCompletionManually(isCompleted)) {
            return userTodoStatusRepository.save(status)
        }
        return status
    }
}
