package com.ssutime.todo.domain

import com.ssutime.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.DynamicUpdate
import java.time.LocalDateTime

@Entity
@Table(
    name = "todo",
    uniqueConstraints = [UniqueConstraint(columnNames = ["subject_id", "material_code"])],
)
@DynamicUpdate
class Todo private constructor(
    @Column(nullable = false)
    val subjectId: Long,
    @Column(nullable = false)
    val materialCode: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: TodoType,
    @Column(nullable = false)
    var dueDate: LocalDateTime,
    @Column(nullable = false)
    var title: String,
    var aiSummary: String? = null,
    var estimatedDurationMinutes: Int? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TodoStatus = TodoStatus.PROVISIONAL,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0

    @Version
    var version: Long = 0

    @Column(name = "attachment_links", columnDefinition = "TEXT")
    private var attachmentLinksText: String? = null

    val attachmentLinks: List<String>
        get() = attachmentLinksText?.split(ATTACHMENT_LINK_SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    fun confirm() {
        status = TodoStatus.CONFIRMED
    }

    fun updateDueDate(newDueDate: LocalDateTime) {
        dueDate = newDueDate
    }

    fun updateAiSummary(summary: String) {
        aiSummary = summary
    }

    fun updateAssignmentAnalysis(
        summary: String,
        estimatedDurationMinutes: Int,
    ) {
        updateAiSummary(summary)
        this.estimatedDurationMinutes = estimatedDurationMinutes.toHalfHourMinutes()
    }

    private fun Int.toHalfHourMinutes(): Int =
        coerceAtLeast(HALF_HOUR_MINUTES).let { minutes ->
            ((minutes + HALF_HOUR_MINUTES - 1) / HALF_HOUR_MINUTES) * HALF_HOUR_MINUTES
        }

    companion object {
        private const val HALF_HOUR_MINUTES = 30

        private const val ATTACHMENT_LINK_SEPARATOR = "\n"

        fun joinAttachmentLinks(links: List<String>): String? {
            require(links.all { it.isNotBlank() && ATTACHMENT_LINK_SEPARATOR !in it }) {
                "attachment links must be single-line non-blank URLs"
            }
            return links.joinToString(ATTACHMENT_LINK_SEPARATOR).ifEmpty { null }
        }

        fun create(
            subjectId: Long,
            materialCode: Long,
            type: TodoType,
            dueDate: LocalDateTime,
            title: String,
        ): Todo {
            require(title.isNotBlank()) { "title must not be blank" }
            return Todo(
                subjectId = subjectId,
                materialCode = materialCode,
                type = type,
                dueDate = dueDate,
                title = title,
            )
        }
    }
}
