package com.sapphirebank.kyc.domain.rules

import com.sapphirebank.kyc.domain.model.AuditEvent
import java.security.MessageDigest

object Hashing {
    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * Stage 8 immutable audit trail.
 * AUD-002: eventHash = sha256(previousEventHash|sequenceNo|occurredAt|eventType|entityId|payloadSha256);
 * genesis previous hash = 64 zeros.
 */
object AuditChain {
    val GENESIS: String = "0".repeat(64)

    data class Verification(val valid: Boolean, val brokenAtSequence: Int?, val message: String)

    fun hashOf(prev: String, seq: Int, occurredAt: String, type: String, entityId: String, payloadSha: String): String =
        Hashing.sha256Hex("$prev|$seq|$occurredAt|$type|$entityId|$payloadSha")

    fun append(
        chain: List<AuditEvent>, occurredAt: String, eventType: String,
        entityId: String, actor: String, payload: String,
    ): List<AuditEvent> {
        val prev = chain.lastOrNull()?.eventHash ?: GENESIS
        val seq = (chain.lastOrNull()?.sequenceNo ?: 0) + 1
        val payloadSha = Hashing.sha256Hex(payload)
        val hash = hashOf(prev, seq, occurredAt, eventType, entityId, payloadSha)
        return chain + AuditEvent(seq, occurredAt, eventType, entityId, actor, payloadSha, prev, hash)
    }

    fun verify(chain: List<AuditEvent>): Verification {
        var prev = GENESIS
        chain.forEachIndexed { i, e ->
            if (e.sequenceNo != i + 1) return Verification(false, e.sequenceNo, "Sequence gap at #${e.sequenceNo}")
            if (e.previousEventHash != prev) return Verification(false, e.sequenceNo, "Broken link at #${e.sequenceNo}")
            val expected = hashOf(prev, e.sequenceNo, e.occurredAt, e.eventType, e.entityId, e.payloadSha256)
            if (expected != e.eventHash) return Verification(false, e.sequenceNo, "Hash mismatch at #${e.sequenceNo} (event altered)")
            prev = e.eventHash
        }
        return Verification(true, null, "Chain intact: ${chain.size} event(s) verified")
    }
}
