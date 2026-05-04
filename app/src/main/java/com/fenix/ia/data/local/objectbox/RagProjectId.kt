package com.fenix.ia.data.local.objectbox

import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

object RagProjectId {
    fun stableLong(projectId: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(projectId.toByteArray(Charsets.UTF_8))
        val raw = ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long
        return abs(raw).takeIf { it != Long.MIN_VALUE } ?: 0L
    }
}
