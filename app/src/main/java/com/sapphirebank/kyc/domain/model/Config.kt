package com.sapphirebank.kyc.domain.model

enum class CountryRisk(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }

/** Configurable parameters of the KYC rule catalogue (SBK-KYC-10). Defaults are the catalogue values. */
data class KycThresholds(
    val nameMatchMin: Double = 0.85,             // KYC-008
    val faceMatchMinKyc008: Double = 0.85,       // KYC-008
    val faceMatchMinVcip: Double = 0.90,         // KYC-006
    val minRandomQuestions: Int = 3,             // KYC-006
    val limitedKycDepositCapInr: Long = 100_000L, // KYC-007
    val limitedKycLoanCapInr: Long = 60_000L,    // KYC-007
    val deemedOvdWindowDays: Int = 90,           // KYC-003
)

data class ProductConfig(
    val product: ProductLine,
    val enabled: Boolean,
    val minAmountInr: Long,
    val maxAmountInr: Long,
    val annualRatePct: Double,
    val maxTenureMonths: Int,
    val tagline: String,
)

/** Illustrative credit-policy thresholds; replace with the bank's approved policy. */
data class EligibilityPolicy(
    val maxDtiPct: Int = 50,
    val referDtiPct: Int = 60,
    val minBureauScore: Int = 700,
    val referBureauScore: Int = 650,
)

data class UserAccount(
    val userId: String,
    val displayName: String,
    val officerRole: String,
    val delegatedLimitInr: Long = 0,
    val active: Boolean = true,
)

data class CustomerAccount(val userId: String, val displayName: String)

object Countries {
    val names: Map<String, String> = mapOf(
        "IN" to "India", "US" to "United States", "GB" to "United Kingdom", "SG" to "Singapore",
        "AE" to "United Arab Emirates", "IR" to "Iran", "KP" to "North Korea", "MM" to "Myanmar",
    )
    fun label(code: String) = names[code]?.let { "$it ($code)" } ?: code
}

data class BankConfig(
    val thresholds: KycThresholds = KycThresholds(),
    val products: List<ProductConfig> = DefaultConfig.products(),
    /** Illustrative defaults: replace with the bank's approved country-risk list. */
    val countryRisk: Map<String, CountryRisk> = DefaultConfig.countryRisk(),
    /** Fictional demo watchlist entries. */
    val watchlist: Set<String> = setOf("Zorin Vexley", "Marta Quillon"),
    val branches: List<String> = listOf("Mumbai Main", "Bengaluru Indiranagar", "Chennai T. Nagar", "Delhi Connaught Place"),
    val employmentTypes: List<String> = listOf("Salaried", "Self-employed", "Business owner", "Retired"),
    val users: List<UserAccount> = DefaultConfig.users(),
    val customers: List<CustomerAccount> = DefaultConfig.customers(),
    val eligibility: EligibilityPolicy = EligibilityPolicy(),
    val dicgcCoverInr: Long = 500_000L,
) {
    fun product(p: ProductLine): ProductConfig = products.first { it.product == p }
    fun user(id: String): UserAccount? = users.firstOrNull { it.userId == id }
    fun activeUsers(officerRole: String) = users.filter { it.active && it.officerRole == officerRole }
}

object DefaultConfig {
    fun products() = listOf(
        ProductConfig(ProductLine.SAVINGS_ACCOUNT, true, 1_000, 1_000_000, 3.0, 0, "Everyday banking with instant KYC"),
        ProductConfig(ProductLine.CURRENT_ACCOUNT, true, 10_000, 2_500_000, 0.0, 0, "For businesses and professionals"),
        ProductConfig(ProductLine.TERM_DEPOSIT, true, 10_000, 2_500_000, 7.0, 120, "Fixed and recurring deposits"),
        ProductConfig(ProductLine.RETAIL_LOAN, true, 50_000, 2_500_000, 10.5, 84, "Personal and retail credit"),
    )

    fun countryRisk() = mapOf(
        "IN" to CountryRisk.LOW, "US" to CountryRisk.LOW, "GB" to CountryRisk.LOW, "SG" to CountryRisk.LOW,
        "AE" to CountryRisk.MEDIUM,
        "IR" to CountryRisk.HIGH, "KP" to CountryRisk.HIGH, "MM" to CountryRisk.HIGH,
    )

    /** Roster from SBK-KYC-14. The first user of each role is unchanged from the original app. */
    fun users() = listOf(
        UserAccount("U-RM-01", "Ananya Iyer", "RELATIONSHIP_MANAGER"),
        UserAccount("U-KYC-01", "Farhan Qureshi", "KYC_OPERATIONS_OFFICER"),
        UserAccount("U-VCP-01", "Sandeep Rao", "VCIP_OFFICIAL"),
        UserAccount("U-CMP-01", "Kavita Menon", "COMPLIANCE_OFFICER"),
        UserAccount("U-CRD-01", "Vikram Desai", "CREDIT_OFFICER", 2_500_000),
        UserAccount("U-BM-01", "Lakshmi Narayanan", "BRANCH_MANAGER", 10_000_000),
        UserAccount("U-SM-01", "Dr. Nandini Rao", "SENIOR_MANAGEMENT", 100_000_000),
        UserAccount("U-AUD-01", "Tanvi Shah", "INTERNAL_AUDITOR"),
        UserAccount("U-KYC-02", "Neha Kulkarni", "KYC_OPERATIONS_OFFICER"),
        UserAccount("U-BM-02", "Arjun Kapoor", "BRANCH_MANAGER", 10_000_000),
        UserAccount("U-ADM-01", "Rahul Bose", "SYSTEM_ADMIN"),
    )

    fun customers() = listOf(
        CustomerAccount("C-1001", "Asha Rao"),
        CustomerAccount("C-1002", "Rohan Mehta"),
        CustomerAccount("C-1003", "Meera Nair"),
        CustomerAccount("C-1004", "Imran Qureshi"),
        CustomerAccount("C-1005", "Hari Prasad Verma"),
        CustomerAccount("C-1006", "Marta Quillon"),
        CustomerAccount("C-1007", "Kiran Patel"),
    )
}

/** Append-only log of administrative changes (users, rules, products, country risk, reference data). */
data class AdminEvent(val at: String, val actor: String, val action: String, val detail: String)
