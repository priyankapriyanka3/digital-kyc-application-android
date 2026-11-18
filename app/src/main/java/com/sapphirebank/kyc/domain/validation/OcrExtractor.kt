package com.sapphirebank.kyc.domain.validation

import com.sapphirebank.kyc.domain.model.DocType

/** Fields recognised in raw OCR text. Pure Kotlin so it is unit-testable without ML Kit. */
data class OcrResult(
    val docType: DocType?,
    val number: String?,
    val name: String?,
    val dateOfBirth: String?,
)

object OcrExtractor {
    private val panRx = Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b")
    private val aadhaarRx = Regex("\\b([2-9][0-9]{3})\\s?([0-9]{4})\\s?([0-9]{4})\\b")
    private val dobRx = Regex("\\b(0?[1-9]|[12][0-9]|3[01])[/-](0?[1-9]|1[0-2])[/-]((?:19|20)[0-9]{2})\\b")
    private val passportRx = Regex("\\b[A-Z][0-9]{7}\\b")

    fun parse(text: String): OcrResult {
        val t = text.uppercase()
        val pan = panRx.find(t)?.value
        val aadhaar = aadhaarRx.find(t)?.let { it.groupValues.drop(1).joinToString("") }
            ?.takeIf { DocumentValidators.isValidAadhaar(it) }
        val passport = passportRx.find(t)?.value.takeIf { t.contains("PASSPORT") }

        val (type, number) = when {
            pan != null -> DocType.PAN to pan
            aadhaar != null -> DocType.AADHAAR to aadhaar
            passport != null -> DocType.PASSPORT to passport
            else -> null to null
        }
        val dob = dobRx.find(text)?.let {
            "%02d/%02d/%s".format(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3])
        }
        return OcrResult(type, number, extractName(text), dob)
    }

    /** Name is the line after a "Name" label, otherwise the first all-alpha uppercase line of 2+ words. */
    private fun extractName(text: String): String? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val idx = lines.indexOfFirst { it.equals("name", true) || it.equals("name:", true) }
        if (idx >= 0 && idx + 1 < lines.size) return lines[idx + 1]
        lines.firstNotNullOfOrNull { Regex("(?i)^name\\s*[:\\-]\\s*(.+)$").find(it)?.groupValues?.get(1) }?.let { return it }
        return lines.firstOrNull {
            it.matches(Regex("^[A-Za-z .]{5,}$")) && it.trim().contains(' ') &&
                listOf("INCOME", "GOVT", "GOVERNMENT", "INDIA", "PERMANENT", "ACCOUNT", "DEPARTMENT", "AUTHORITY").none { k -> it.uppercase().contains(k) }
        }
    }
}
