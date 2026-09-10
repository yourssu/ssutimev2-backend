package com.ssutime.assignmentanalysis.application

import com.ssutime.assignmentanalysis.domain.AssignmentAnalysis
import com.ssutime.assignmentanalysis.domain.AssignmentAnalysisPrepared
import com.ssutime.assignmentanalysis.infrastructure.AssignmentAnalysisRepository
import com.ssutime.assignmentanalysis.presentation.AssignmentAnalysisPayload
import com.ssutime.assignmentanalysis.presentation.AssignmentAnalysisResponse
import com.ssutime.todo.domain.Todo
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest

@Service
class AssignmentAnalysisPreparationService(
    private val contentExtractor: AssignmentContentExtractor,
    private val assignmentAnalysisRepository: AssignmentAnalysisRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
) {
    @Transactional
    fun prepareAnalysis(
        todo: Todo,
        payload: AssignmentAnalysisPayload,
    ): AssignmentAnalysisResponse {
        val extracted = contentExtractor.extract(payload)
        val contentHash = sha256(extracted.sanitizedContent)
        val analysis =
            assignmentAnalysisRepository.findByTodoAndCourseIdAndAssignmentIdAndContentHash(
                todo = todo,
                courseId = payload.courseId,
                assignmentId = payload.assignmentId,
                contentHash = contentHash,
            ) ?: assignmentAnalysisRepository
                .save(
                    AssignmentAnalysis.create(
                        todo = todo,
                        courseId = payload.courseId,
                        assignmentId = payload.assignmentId,
                        contentHash = contentHash,
                        sanitizedContent = extracted.sanitizedContent,
                        skippedFiles = extracted.skippedFiles.joinToString("\n"),
                    ),
                ).also { saved ->
                    applicationEventPublisher.publishEvent(AssignmentAnalysisPrepared(saved.id))
                }

        return AssignmentAnalysisResponse(
            analysisId = analysis.id,
            status = analysis.status,
            skippedFiles = analysis.skippedFiles.lines().filter { it.isNotBlank() },
        )
    }

    fun extractAttachmentLinks(payload: AssignmentAnalysisPayload): List<String> = contentExtractor.extractAttachmentLinks(payload)

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
