package com.sapphirebank.kyc.domain.validation

import com.sapphirebank.kyc.domain.model.DocType

/**
 * Regex validators and masking helpers. Patterns come from the document catalogue (SBK-KYC-11).
 * DOC-001: number must match docType.numberPattern. DOC-003: Aadhaar first 8 digits masked.
 */
object DocumentValidators {

    val PAN_REGEX = Regex("^[A-Z]{5}[0-9]{4}[A-Z]$")
    val AADHAAR_REGEX = Regex("^[0-9]{12}$")
    val PASSPORT_REGEX = Regex("^[A-Z][0-9]{7}$")
    val VOTER_ID_REGEX = Regex("^[A-Z]{3}[0-9]{7}$")

    /** Indian driving licence: state code + RTO code + year + serial (catalogue has no pattern; format is common practice). */
    val DL_REGEX = Regex("^[A-Z]{2}[0-9]{2}[0-9]{4}[0-9]{7}$")

    /** Verhoeff tables for the Aadhaar check digit. */
    private val d = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 2, 3, 4, 0, 6, 7, 8, 9, 5),
        intArrayOf(2, 3, 4, 0, 1, 7, 8, 9, 5, 6), intArrayOf(3, 4, 0, 1, 2, 8, 9, 5, 6, 7),
        intArrayOf(4, 0, 1, 2, 3, 9, 5, 6, 7, 8), intArrayOf(5, 9, 8, 7, 6, 0, 4, 3, 2, 1),
        intArrayOf(6, 5, 9, 8, 7, 1, 0, 4, 3, 2), intArrayOf(7, 6, 5, 9, 8, 2, 1, 0, 4, 3),
        intArrayOf(8, 7, 6, 5, 9, 3, 2, 1, 0, 4), intArrayOf(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
    )
    private val p = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), intArrayOf(1, 5, 7, 6, 2, 8, 3, 0, 9, 4),
        intArrayOf(5, 8, 0, 3, 7, 9, 6, 1, 4, 2), intArrayOf(8, 9, 1, 6, 0, 4, 3, 5, 2, 7),
        intArrayOf(9, 4, 5, 3, 1, 2, 6, 8, 7, 0), intArrayOf(4, 2, 8, 6, 5, 7, 3, 9, 0, 1),
        intArrayOf(2, 7, 9, 3, 8, 0, 6, 4, 1, 5), intArrayOf(7, 0, 4, 6, 9, 1, 3, 2, 5, 8),
    )

    fun verhoeffValid(number: String): Boolean {
        if (number.any { !it.isDigit() }) return false
        var c = 0
        number.reversed().forEachIndexed { i, ch -> c = d[c][p[i % 8][ch - '0']] }
        return c == 0
    }

    /** Aadhaar never starts with 0 or 1. */
    fun isValidAadhaar(raw: String): Boolean {
        val n = raw.filter { it.isDigit() }
        return AADHAAR_REGEX.matches(n) && n[0] >= '2' && verhoeffValid(n)
    }

    fun isValidPan(raw: String): Boolean = PAN_REGEX.matches(raw.trim().uppercase())

    /** 4th PAN char encodes holder type (P = individual, C = company, F = firm, ...). */
    fun panHolderType(pan: String): String? = when (pan.trim().uppercase().getOrNull(3)) {
        'P' -> "Individual"; 'C' -> "Company"; 'F' -> "Firm / LLP"; 'H' -> "HUF"
        'A' -> "AOP"; 'T' -> "Trust"; 'B' -> "BOI"; 'L' -> "Local authority"
        'J' -> "Artificial juridical person"; 'G' -> "Government"
        else -> null
    }

    fun validateNumber(type: DocType, raw: String): ValidationResult {
        val v = raw.trim().uppercase()
        return when (type) {
            DocType.PAN ->
                if (isValidPan(v)) ValidationResult.ok("PAN format valid (${panHolderType(v) ?: "unknown holder type"})")
                else ValidationResult.fail("PAN must match AAAAA9999A")
            DocType.AADHAAR ->
                if (isValidAadhaar(v)) ValidationResult.ok("Aadhaar format and Verhoeff checksum valid")
                else ValidationResult.fail("Aadhaar must be 12 digits (not starting 0/1) with a valid checksum")
            DocType.PASSPORT ->
                if (PASSPORT_REGEX.matches(v)) ValidationResult.ok("Passport format valid")
                else ValidationResult.fail("Passport must match A9999999")
            DocType.VOTER_ID ->
                if (VOTER_ID_REGEX.matches(v)) ValidationResult.ok("EPIC format valid")
                else ValidationResult.fail("Voter ID must match AAA9999999")
            DocType.DRIVING_LICENCE ->
                if (DL_REGEX.matches(v.replace("-", "").replace(" ", ""))) ValidationResult.ok("Driving licence format valid")
                else ValidationResult.fail("Driving licence must look like MH1220190001234")
            DocType.FORM60 -> ValidationResult.ok("Form 60 – no number")
            DocType.UTILITY_BILL ->
                if (v.length >= 6) ValidationResult.ok("Consumer number captured")
                else ValidationResult.fail("Enter the consumer number")
        }
    }

    /** DOC-003: hide the first 8 digits of Aadhaar; show last 4. PAN: show first 2 and last 2. */
    fun mask(type: DocType, raw: String): String {
        val v = raw.filter { !it.isWhitespace() }
        return when (type) {
            DocType.AADHAAR -> if (v.length == 12) "XXXX XXXX ${v.takeLast(4)}" else "•".repeat(v.length)
            DocType.PAN -> if (v.length == 10) v.take(2) + "••••••" + v.takeLast(2) else "•".repeat(v.length)
            else -> if (v.length > 4) v.take(2) + "•".repeat(v.length - 4) + v.takeLast(2) else "•".repeat(v.length)
        }
    }

    /** Name similarity in [0,1] (token-set Dice on lower-cased words) – used for KYC-008 / DOC-004 (>= 0.85). */
    fun nameMatchScore(a: String, b: String): Double {
        fun toks(s: String) = s.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }.toSet()
        val x = toks(a)
        val y = toks(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 2.0 * x.intersect(y).size / (x.size + y.size)
    }
}

data class ValidationResult(val valid: Boolean, val message: String) {
    companion object {
        fun ok(msg: String) = ValidationResult(true, msg)
        fun fail(msg: String) = ValidationResult(false, msg)
    }
}
