package com.ssutime.todo

import com.ssutime.todo.domain.Todo

fun Todo.setAttachmentLinksForTest(links: List<String>) {
    Todo::class.java
        .getDeclaredField("attachmentLinksText")
        .apply { isAccessible = true }
        .set(this, Todo.joinAttachmentLinks(links))
}
