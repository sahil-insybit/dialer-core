package com.fayyaztech.dialer_app.recording

/** One recording as returned by the API's GET /recordings. */
data class Recording(
    val id: String,
    val phoneNumber: String,
    val direction: String,
    val durationMs: Long,
    val url: String,
    val createdAt: String
)
