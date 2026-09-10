package com.ssutime.todo

import com.fasterxml.jackson.databind.ObjectMapper
import com.ssutime.todo.domain.Todo
import com.ssutime.todo.domain.TodoType
import com.ssutime.todo.domain.UserTodoStatus
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodoAttachmentLinksTest {
    private val objectMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json().build()
    private val todo = Todo.create(10L, 20L, TodoType.ASSIGNMENT, LocalDateTime.of(2026, 9, 20, 23, 59), "실습과제 3")
    private val links =
        listOf(
            "https://canvas.ssu.ac.kr/courses/44383/files/1/download",
            "https://canvas.ssu.ac.kr/courses/44383/files/2/download",
        )

    @Test
    fun `attachmentLinks is empty by default`() {
        assertEquals(emptyList(), todo.attachmentLinks)
    }

    @Test
    fun `attachmentLinks round-trips through joined storage text`() {
        todo.setAttachmentLinksForTest(links)

        assertEquals(links, todo.attachmentLinks)
    }

    @Test
    fun `joinAttachmentLinks stores empty list as null`() {
        assertNull(Todo.joinAttachmentLinks(emptyList()))
    }

    @Test
    fun `joinAttachmentLinks rejects multi-line or blank link`() {
        assertFailsWith<IllegalArgumentException> {
            Todo.joinAttachmentLinks(listOf("https://canvas.ssu.ac.kr/a\nhttps://canvas.ssu.ac.kr/b"))
        }
        assertFailsWith<IllegalArgumentException> { Todo.joinAttachmentLinks(listOf(" ")) }
    }

    @Test
    fun `todo list item serializes attachmentLinks as array`() {
        todo.setAttachmentLinksForTest(links)

        val json = objectMapper.readTree(objectMapper.writeValueAsString(UserTodoStatus.create(1L, todo, 60)))
        val todoJson = json["todo"]

        assertTrue(todoJson["attachmentLinks"].isArray)
        assertEquals(links, todoJson["attachmentLinks"].map { it.asText() })
        assertFalse(todoJson.has("attachmentLinksText"))
    }

    @Test
    fun `todo without attachments serializes empty attachmentLinks array`() {
        val json = objectMapper.readTree(objectMapper.writeValueAsString(todo))

        assertTrue(json["attachmentLinks"].isArray)
        assertEquals(0, json["attachmentLinks"].size())
    }
}
