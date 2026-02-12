package com.lhstack.https

import java.time.LocalDateTime

enum class HistorySourceType {
    TAB,
    SAVED
}

data class HttpApiGroup(
    var id: Long = 0,
    var parentId: Long? = null,
    var name: String = "",
    var sortIndex: Int = 0,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

data class HttpSavedRequest(
    var id: Long = 0,
    var groupId: Long? = null,
    var name: String = "",
    var draft: HttpRequestDraft = HttpRequestDraft(),
    var sortIndex: Int = 0,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

data class HttpCallTab(
    var id: Long = 0,
    var title: String = "",
    var savedRequestId: Long? = null,
    var draft: HttpRequestDraft = HttpRequestDraft(),
    var sortIndex: Int = 0,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

data class HttpRequestHistoryEntry(
    var id: Long = 0,
    var sourceType: HistorySourceType = HistorySourceType.TAB,
    var sourceId: Long? = null,
    var request: HttpRequestDraft = HttpRequestDraft(),
    var response: HttpResponseSnapshot? = null,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    val method: String
        get() = request.method

    val url: String
        get() = request.url
}
