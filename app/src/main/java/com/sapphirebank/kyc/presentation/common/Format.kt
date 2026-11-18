package com.sapphirebank.kyc.presentation.common

import com.sapphirebank.kyc.domain.model.Actor
import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.ApplicationStatus.*
import com.sapphirebank.kyc.domain.model.CaseFile
import com.sapphirebank.kyc.domain.model.Role
import com.sapphirebank.kyc.domain.model.RuleOutcome
import com.sapphirebank.kyc.domain.model.ScreeningStatus
import com.sapphirebank.kyc.domain.model.Tone
import com.sapphirebank.kyc.domain.rules.DualControlEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.of("Asia/Kolkata"))

fun formatTime(iso: String): String =
    runCatching { timeFmt.format(Instant.parse(iso)) }.getOrDefault(iso)

fun humanize(s: String): String =
    s.replace('_', ' ').split(Regex("(?<=[a-z])(?=[A-Z])|\\s+")).filter { it.isNotBlank() }
        .joinToString(" ") { it.lowercase() }.replaceFirstChar { it.uppercase() }

fun ApplicationStatus.label(): String = humanize(name)

fun ApplicationStatus.tone(): Tone = when (this) {
    FULFILLED, APPROVED -> Tone.GOOD
    REJECTED -> Tone.BAD
    EDD_PENDING, DOCUMENTS_PENDING -> Tone.WATCH
    else -> Tone.INFO
}

/** The one chip that best describes where a case is right now, in plain words. */
fun CaseFile.displayStatus(): Pair<String, Tone> = when {
    status == WITHDRAWN || status == EXPIRED -> status.label() to Tone.INFO
    status.terminal -> status.label() to status.tone()
    hold != null -> "On hold" to Tone.BAD
    openInfoRequests.isNotEmpty() -> "Info requested" to Tone.WATCH
    screening?.status == ScreeningStatus.FLAGGED -> "Flagged" to Tone.BAD
    status == PENDING_REVIEW -> "Pending review" to Tone.WATCH
    status == KYC_IN_PROGRESS -> "In review" to Tone.INFO
    else -> status.label() to status.tone()
}

fun RuleOutcome.tone(): Tone = when (this) {
    RuleOutcome.PASS -> Tone.GOOD
    RuleOutcome.FAIL -> Tone.BAD
    RuleOutcome.REFER -> Tone.WATCH
    RuleOutcome.OVERRIDDEN -> Tone.INFO
    RuleOutcome.PENDING, RuleOutcome.NOT_APPLICABLE -> Tone.INFO
}

fun RuleOutcome.label(): String = humanize(name)

/** Short list of things that need the signed-in user's attention. Derived from state, so it is always current. */
fun notificationsFor(actor: Actor, cases: List<CaseFile>): List<String> = when (actor.role) {
    Role.CUSTOMER -> cases.flatMap { c ->
        buildList {
            c.openInfoRequests.forEach { add("${c.arn}: more information needed. ${it.message}") }
            if (c.status == FULFILLED) add("${c.arn}: your account is open. See your welcome kit.")
            if (c.status == REJECTED) add("${c.arn}: your application was not approved.")
        }
    }
    Role.KYC_ANALYST -> cases.filter { it.status == KYC_IN_PROGRESS || it.status == DOCUMENTS_VALIDATED }
        .map { "${it.arn}: ${it.application.customerName} is waiting for KYC review" }
    Role.CREDIT_OFFICER -> cases.filter { it.status == PENDING_REVIEW && DualControlEngine.nextLevel(it)?.name == "L1_CREDIT_OFFICER" }
        .map { "${it.arn}: eligibility review needed" }
    Role.BRANCH_MANAGER -> cases.filter { c ->
        val lvl = DualControlEngine.levelFor(actor.officerRole)
        c.status == PENDING_REVIEW && lvl != null && DualControlEngine.nextLevel(c) == lvl
    }.map { "${it.arn}: final decision needed" }
    Role.COMPLIANCE_OFFICER -> cases.filter { it.screening?.status == ScreeningStatus.FLAGGED }
        .map { "${it.arn}: AML/PEP alert for ${it.application.customerName}" }
    Role.SYSTEM_ADMIN -> emptyList()
}

/** What a customer sees. Screening flags, holds and risk are never disclosed to the customer. */
fun CaseFile.customerStatus(): Pair<String, Tone> = when {
    status == FULFILLED -> "Account open" to Tone.GOOD
    status == REJECTED -> "Not approved" to Tone.BAD
    status == WITHDRAWN -> "Withdrawn" to Tone.INFO
    status == EXPIRED -> "Expired" to Tone.INFO
    openInfoRequests.isNotEmpty() -> "Action needed" to Tone.WATCH
    status == APPROVED -> "Approved" to Tone.GOOD
    else -> "In progress" to Tone.INFO
}

fun CaseFile.progressFraction(): Float = if (status == FULFILLED) 1f else status.stage.number / 8f

fun isToday(iso: String): Boolean = runCatching {
    Instant.parse(iso).atZone(ZoneId.of("Asia/Kolkata")).toLocalDate() == java.time.LocalDate.now(ZoneId.of("Asia/Kolkata"))
}.getOrDefault(false)
