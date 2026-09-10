package com.ssutime.todo.infrastructure

import com.ssutime.todo.domain.Todo
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface TodoRepository : JpaRepository<Todo, Long> {
    fun findBySubjectIdAndMaterialCode(
        subjectId: Long,
        materialCode: Long,
    ): Todo?

    // 공유 Todo의 @Version을 올리지 않는 벌크 업데이트: 동시 제보 간 낙관적 락 충돌로 본인 제보가 롤백되지 않게 한다.
    @Modifying
    @Query("UPDATE Todo t SET t.attachmentLinksText = :attachmentLinksText WHERE t.id = :todoId")
    fun updateAttachmentLinks(
        todoId: Long,
        attachmentLinksText: String?,
    ): Int
}
