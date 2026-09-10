package com.ssutime.todo

import com.ssutime.todo.domain.Todo
import com.ssutime.todo.domain.TodoType
import com.ssutime.todo.infrastructure.TodoRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import java.time.LocalDateTime
import kotlin.test.assertEquals

@DataJpaTest
class TodoRepositoryTest
    @Autowired
    constructor(
        private val todoRepository: TodoRepository,
        private val entityManager: TestEntityManager,
    ) {
        @Test
        fun `updateAttachmentLinks persists links without bumping optimistic lock version`() {
            val todo =
                todoRepository.saveAndFlush(
                    Todo.create(10L, 20L, TodoType.ASSIGNMENT, LocalDateTime.of(2026, 9, 20, 23, 59), "실습과제 3"),
                )
            val versionBefore = todo.version
            val links =
                listOf(
                    "https://canvas.ssu.ac.kr/courses/44383/files/1/download",
                    "https://canvas.ssu.ac.kr/courses/44383/files/2/download",
                )

            val updatedRows = todoRepository.updateAttachmentLinks(todo.id, Todo.joinAttachmentLinks(links))
            entityManager.clear()
            val reloaded = todoRepository.findById(todo.id).orElseThrow()

            assertEquals(1, updatedRows)
            assertEquals(links, reloaded.attachmentLinks)
            assertEquals(versionBefore, reloaded.version)
        }
    }
