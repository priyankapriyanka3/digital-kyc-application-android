package com.sapphirebank.kyc.data

import com.sapphirebank.kyc.domain.model.Actor
import com.sapphirebank.kyc.domain.model.ApplicationStatus
import com.sapphirebank.kyc.domain.model.CustomerApplication
import com.sapphirebank.kyc.domain.model.DefaultConfig
import com.sapphirebank.kyc.domain.model.DocType
import com.sapphirebank.kyc.domain.model.DocumentKind
import com.sapphirebank.kyc.domain.model.DocumentRecord
import com.sapphirebank.kyc.domain.model.DocumentRequirements
import com.sapphirebank.kyc.domain.model.FinancialProfile
import com.sapphirebank.kyc.domain.model.KycMode
import com.sapphirebank.kyc.domain.model.Officer
import com.sapphirebank.kyc.domain.model.ProductLine
import com.sapphirebank.kyc.domain.usecase.CaseService
import com.sapphirebank.kyc.domain.usecase.NewApplication
import com.sapphirebank.kyc.domain.usecase.Step
import java.time.Instant

/**
 * Fictional demo data. Every name, PAN and Aadhaar below is invented; the Aadhaar numbers only satisfy the Verhoeff
 * checksum so the validators accept them. Cases are created through [CaseService], so seeding exercises the same
 * authorization, validation and audit rules as the real flows.
 */
object SeedData {
    /** Staff roster as Officer records (kept for the dual-control engine and tests). */
    val officers: List<Officer> = DefaultConfig.users().map { Officer(it.userId, it.displayName, it.officerRole, it.delegatedLimitInr) }

    val bureauScores: Map<String, Int> = mapOf(
        "MNRPN4321K" to 782,   // Meera Nair
        "HPVPV9087D" to 735,   // Hari Prasad Verma
        "RMEPM2468C" to 712,   // Rohan Mehta
    )

    private fun app(
        name: String, dob: String, product: ProductLine, amount: Long, pan: String, aadhaar: String,
        pep: Boolean = false, country: String = "IN",
    ) = CustomerApplication(
        arn = "", customerName = name, dateOfBirth = dob, productLine = product, sanctionAmountInr = amount,
        panNumber = pan, form60Submitted = false, identityDoc = DocType.AADHAAR, identityDocNumber = aadhaar,
        addressDoc = DocType.AADHAAR, addressDocNumber = aadhaar, kycMode = KycMode.IN_PERSON,
        aadhaarConsentGiven = false, isPep = pep, isNonIndividual = false, beneficialOwnerRecorded = false,
        status = ApplicationStatus.DRAFT, countryCode = country,
    )

    private fun docs(a: CustomerApplication, by: String): List<DocumentRecord> {
        val at = Instant.now().toString()
        return DocumentRequirements.required(a).map { DocumentRecord(it, it.name.lowercase() + ".jpg", null, at, by) }
    }

    suspend fun populate(svc: CaseService) {
        val everyone = svc.directory().values.flatten()
        fun who(id: String): Actor = everyone.first { it.userId == id }

        suspend fun open(customerId: String, a: CustomerApplication, fin: FinancialProfile? = null): String {
            val c = who(customerId)
            val r = svc.submit(c, NewApplication(a, docs(a, customerId), fin, ocrName = a.customerName, ocrDob = a.dateOfBirth))
            check(r is Step.Ok) { "seed submit failed for ${a.customerName}: $r" }
            return r.case.arn
        }

        fun analystFor(customerId: String, arn: String): Actor {
            val id = svc.caseFor(who(customerId), arn)!!.assignedAnalystId!!
            return who(id)
        }

        suspend fun verifyKyc(customerId: String, arn: String) {
            val an = analystFor(customerId, arn)
            check(svc.startKyc(an, arn) is Step.Ok)
            svc.searchCkycr(an, arn)
            val r = svc.kycApprove(an, arn, "Documents verified against originals", manualCompared = true)
            check(r is Step.Ok) { "seed KYC approve failed for $arn: $r" }
        }

        // 1. Asha: fresh savings application waiting in the analyst queue.
        open("C-1001", app("Asha Rao", "15/04/1990", ProductLine.SAVINGS_ACCOUNT, 25_000, "ABCPR1234F", "345678912342"))

        // 2. Rohan: current account, KYC already started (In Review).
        val rohan = open("C-1002", app("Rohan Mehta", "02/09/1985", ProductLine.CURRENT_ACCOUNT, 150_000, "RMEPM2468C", "456789123451"))
        check(svc.startKyc(analystFor("C-1002", rohan), rohan) is Step.Ok)

        // 3. Meera: retail loan, KYC approved, waiting for the Credit Officer and Branch Manager.
        val meera = open(
            "C-1003", app("Meera Nair", "21/07/1992", ProductLine.RETAIL_LOAN, 500_000, "MNRPN4321K", "567891234564"),
            FinancialProfile(monthlyIncomeInr = 75_000, existingEmiInr = 5_000, employmentType = "Salaried", tenureMonths = 36),
        )
        verifyKyc("C-1003", meera)

        // 4. Imran: fixed deposit with an open information request (blocks any decision).
        val imran = open("C-1004", app("Imran Qureshi", "11/01/1978", ProductLine.TERM_DEPOSIT, 300_000, "IQRPQ7788B", "678912345675"))
        val an = analystFor("C-1004", imran)
        check(svc.startKyc(an, imran) is Step.Ok)
        svc.requestInfo(an, imran, "The address proof is blurred. Please upload a clearer copy.", listOf(DocumentKind.ADDRESS_PROOF))

        // 5. Hari: PEP retail loan, flagged for the Compliance Officer.
        val hari = open(
            "C-1005", app("Hari Prasad Verma", "30/03/1970", ProductLine.RETAIL_LOAN, 800_000, "HPVPV9087D", "789123456789", pep = true),
            FinancialProfile(monthlyIncomeInr = 120_000, existingEmiInr = 12_000, employmentType = "Business owner", tenureMonths = 48),
        )
        verifyKyc("C-1005", hari)

        // 6. Marta: savings with a watchlist name match, flagged for the Compliance Officer.
        val marta = open("C-1006", app("Marta Quillon", "05/12/1988", ProductLine.SAVINGS_ACCOUNT, 40_000, "MQLPQ5566R", "298765432101"))
        verifyKyc("C-1006", marta)

        // 7. Kiran: clean savings account, auto-approved after KYC and already opened.
        val kiran = open("C-1007", app("Kiran Patel", "17/06/1995", ProductLine.SAVINGS_ACCOUNT, 10_000, "KPTPL3141S", "345678912342"))
        verifyKyc("C-1007", kiran)
    }
}
