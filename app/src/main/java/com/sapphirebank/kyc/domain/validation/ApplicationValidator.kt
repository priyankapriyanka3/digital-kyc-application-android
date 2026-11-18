package com.sapphirebank.kyc.domain.validation

import com.sapphirebank.kyc.domain.model.CustomerApplication
import com.sapphirebank.kyc.domain.model.DocType
import com.sapphirebank.kyc.domain.model.KycMode
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

enum class FormField { NAME, DOB, AMOUNT, PAN, IDENTITY, ADDRESS, CONSENT }

/** Stage 1/2 form validation (DOC-001 number patterns, DOC-004 PAN format, consent for e-KYC). */
object ApplicationValidator {
    val DOB_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)

    fun parseDob(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim(), DOB_FORMAT) }.getOrNull()

    fun validate(a: CustomerApplication, today: LocalDate): Map<FormField, String> {
        val e = linkedMapOf<FormField, String>()

        if (!a.customerName.trim().matches(Regex("^[A-Za-z][A-Za-z .'-]{2,}$"))) e[FormField.NAME] = "Enter the full name as on the document"

        val dob = parseDob(a.dateOfBirth)
        when {
            dob == null -> e[FormField.DOB] = "Use dd/MM/yyyy"
            dob.isAfter(today) -> e[FormField.DOB] = "Date of birth is in the future"
            Period.between(dob, today).years < 18 -> e[FormField.DOB] = "Applicant must be at least 18"
            Period.between(dob, today).years > 120 -> e[FormField.DOB] = "Check the date of birth"
        }

        if (a.sanctionAmountInr <= 0) e[FormField.AMOUNT] = "Enter an amount greater than 0"

        // KYC-001: PAN XOR Form 60
        when {
            a.panNumber.isNotBlank() && a.form60Submitted -> e[FormField.PAN] = "Provide PAN or Form 60, not both"
            a.panNumber.isNotBlank() && !DocumentValidators.isValidPan(a.panNumber) -> e[FormField.PAN] = "PAN must match AAAAA9999A"
            a.panNumber.isBlank() && !a.form60Submitted -> e[FormField.PAN] = "Enter PAN or tick Form 60"
        }

        // KYC-002 / DOC-001
        if (!a.identityDoc.provesIdentity || a.identityDoc == DocType.FORM60) {
            e[FormField.IDENTITY] = "${a.identityDoc.label} cannot prove identity"
        } else {
            val r = DocumentValidators.validateNumber(a.identityDoc, a.identityDocNumber)
            if (!r.valid) e[FormField.IDENTITY] = r.message
        }

        // KYC-003
        if (!a.addressDoc.provesAddress) {
            e[FormField.ADDRESS] = "${a.addressDoc.label} cannot prove address"
        } else {
            val r = DocumentValidators.validateNumber(a.addressDoc, a.addressDocNumber)
            if (!r.valid) e[FormField.ADDRESS] = r.message
        }

        // KYC-004
        if (a.kycMode == KycMode.AADHAAR_OTP_EKYC) {
            if (!a.aadhaarConsentGiven) e[FormField.CONSENT] = "Explicit Aadhaar consent is required before OTP e-KYC"
            else if (a.identityDoc != DocType.AADHAAR) e[FormField.CONSENT] = "OTP e-KYC needs Aadhaar as the identity document"
        }
        return e
    }
}
