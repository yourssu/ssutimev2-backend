package com.ssutime.todo.presentation

import com.ssutime.assignmentanalysis.application.AssignmentAnalysisPreparationService
import com.ssutime.assignmentanalysis.presentation.AssignmentAnalysisResponse
import com.ssutime.assignmentanalysis.presentation.TodoReportWithAnalysisRequest
import com.ssutime.todo.application.TodoService
import com.ssutime.todo.domain.UserTodoStatus
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/todo")
@Tag(name = "Todos", description = "할 일 제보 및 인증된 사용자의 할 일 상태 API")
class TodoController(
    private val todoService: TodoService,
    private val assignmentAnalysisPreparationService: AssignmentAnalysisPreparationService,
) {
    @PostMapping("/report")
    @Operation(
        summary = "LMS 할 일 제보",
        description =
            "LMS 콘텐츠에서 발견한 할 일을 생성하거나 갱신하고 인증된 사용자와 연결합니다. " +
                "type이 SUBMITTED 또는 SUBMITTED_LATE이면 사용자 할 일을 완료 처리합니다. " +
                "알림 예정 시각은 dueDate에서 계정의 notificationThresholdMinutes를 뺀 값으로 계산됩니다.",
    )
    fun report(
        @Parameter(hidden = true)
        @AuthenticationPrincipal userId: Long,
        @RequestBody request: TodoReportRequest,
    ): ResponseEntity<Unit> {
        todoService.processReport(
            userId = userId,
            subjectId = request.subjectId,
            materialCode = request.materialCode,
            type = request.type,
            dueDate = request.dueDate,
            title = request.title,
        )
        return ResponseEntity.ok().build()
    }

    @PostMapping("/report-with-analysis")
    @Operation(
        summary = "LMS 할 일 제보 및 과제 첨부 AI 분석 요청",
        description =
            "기존 할 일 제보를 처리하면서 assignmentHtml의 Canvas 첨부파일 링크를 할 일에 저장하고" +
                "(첨부 링크가 없는 제보는 기존 링크를 지우지 않습니다), " +
                "요청 범위 LMS 인증정보로 Canvas 첨부를 다운로드/추출해 비동기 AI 분석을 예약합니다.",
    )
    fun reportWithAnalysis(
        @Parameter(hidden = true)
        @AuthenticationPrincipal userId: Long,
        @RequestBody request: TodoReportWithAnalysisRequest,
    ): ResponseEntity<AssignmentAnalysisResponse> {
        val attachmentLinks = assignmentAnalysisPreparationService.extractAttachmentLinks(request.assignmentAnalysis)
        val todo =
            todoService.processReport(
                userId = userId,
                subjectId = request.subjectId,
                materialCode = request.materialCode,
                type = request.type,
                dueDate = request.dueDate,
                title = request.title,
                attachmentLinks = attachmentLinks,
            )
        return ResponseEntity.ok(
            assignmentAnalysisPreparationService.prepareAnalysis(
                todo = todo,
                payload = request.assignmentAnalysis,
            ),
        )
    }

    @GetMapping("/todos")
    @Operation(
        summary = "사용자 할 일 목록 조회",
        description =
            "인증된 사용자의 할 일 상태를 조회합니다. 완료 여부와 알림 예정 시각 관련 필드가 포함됩니다. " +
                "첨부파일이 있는 과제는 todo.attachmentLinks에 Canvas 첨부파일 다운로드 링크 배열이 담기며, 없으면 빈 배열입니다.",
    )
    fun getTodos(
        @Parameter(hidden = true)
        @AuthenticationPrincipal userId: Long,
    ): ResponseEntity<List<UserTodoStatus>> = ResponseEntity.ok(todoService.getUserTodoStatuses(userId))

    @PatchMapping("/todos/{statusId}/completion")
    @Operation(
        summary = "사용자 할 일 완료 상태 변경",
        description = "인증된 사용자가 자신의 과제, 퀴즈 등 할 일을 직접 완료 처리하거나 미완료로 되돌립니다.",
    )
    fun updateCompletion(
        @Parameter(hidden = true)
        @AuthenticationPrincipal userId: Long,
        @Parameter(description = "변경할 사용자 할 일 상태 ID입니다.", example = "1")
        @PathVariable statusId: Long,
        @RequestBody request: TodoCompletionRequest,
    ): ResponseEntity<UserTodoStatus> = ResponseEntity.ok(todoService.updateCompletion(userId, statusId, request.isCompleted))
}
