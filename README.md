# Digital KYC Application (Android)

Sapphire Bank KYC and account-opening app. Kotlin, Jetpack Compose, Material 3, Clean Architecture.
Business rules come from the Sapphire Bank KYC knowledge base (Context Studio `ctx_20b1c072de75`):
rule catalogue KYC-001..009, document rules DOC-001..004, state machine, dual control DCS-001..006
and audit rules AUD-001/002.

## Screens

| # | Screen | What it does |
|---|--------|--------------|
| 1 | **Application** | Applicant details, PAN / Form 60, identity and address OVD, KYC mode. ML Kit on-device OCR fills PAN, Aadhaar, passport number, name and DOB from an image. Regex and checksum validation (PAN `^[A-Z]{5}[0-9]{4}[A-Z]$`, Aadhaar 12 digits with Verhoeff). Live pre-check of KYC-001..004. Aadhaar and PAN are masked after submit (DOC-003). |
| 2 | **Tracker** | 8-stage stepper (S1 to S8) driven by the state machine, risk-score badge, V-CIP / liveness form evaluated against KYC-006, CKYCR search (KYC-005), KYC completion, AML and eligibility advance. |
| 3 | **Review** | Dual-control sign-off (L1 Credit Officer, L2 Branch Manager, L3 Senior Management for PEP / high risk), KYC and DCS rule evaluations, hash-chained audit log with **Verify chain** and a **Simulate tamper** demo. |

## Architecture

```
app/src/main/java/com/sapphirebank/kyc
├── domain/            pure Kotlin, no Android imports, unit-tested on the JVM
│   ├── model/         LifecycleStage, ApplicationStatus, CustomerApplication, CaseFile, ...
│   ├── validation/    DocumentValidators (PAN, Aadhaar+Verhoeff, masking), OcrExtractor, ApplicationValidator
│   ├── rules/         KycRuleEngine, RiskEngine, DualControlEngine, AuditChain, ApplicationStateMachine
│   ├── usecase/       CaseWorkflow (pure transitions) and use cases
│   └── repository/    CaseRepository, DocumentTextRecognizer (interfaces)
├── data/              InMemoryCaseRepository + SeedData (officer roster), MlKitTextRecognizer
└── presentation/      Compose screens + ViewModels (application, tracker, review), theme, common components
```

`KycApp` holds a small manual DI container (`AppContainer`). `CaseRepository` is the persistence
boundary, so Room or a REST client can replace the in-memory store without touching other layers.

## Rule mapping

| Rule | Where |
|------|-------|
| KYC-001 PAN XOR Form 60 | `ApplicationValidator`, `KycRuleEngine` |
| KYC-002 / 003 identity and address OVD | `DocumentValidators`, `KycRuleEngine` |
| KYC-004 Aadhaar explicit consent | `ApplicationValidator`, `KycRuleEngine` |
| KYC-005 CKYCR search (advisory) | `SearchCkycr`, `KycRuleEngine` |
| KYC-006 V-CIP standards (10 checks) | Tracker V-CIP form, `KycRuleEngine` |
| KYC-009 beneficial owner | `KycRuleEngine` |
| DCS-001..004, 006 | `DualControlEngine` |
| AUD-002 `sha256(prev\|seq\|at\|type\|entity\|payloadSha)`, genesis = 64 zeros | `AuditChain` |

**Illustrative parts:** the knowledge base does not publish risk-scoring weights, so `RiskEngine`
weights are placeholders (`METHODOLOGY_VERSION = "illustrative-1"`). Replace with the bank's approved
methodology. Officer ids and names in `SeedData` are sample data. Aadhaar OTP e-KYC and CKYCR calls are
simulated; there is no backend.

## Build and test

Requirements: JDK 17, Android SDK 34 (Android Studio Koala or newer).

```bash
./gradlew testDebugUnitTest   # domain unit tests (JUnit 4)
./gradlew assembleDebug       # debug APK
```

Sample data: tap **Fill sample** on Screen 1 (PAN `ABCDE1234F`, Aadhaar `2345 6789 0124`).
Suggested demo: submit, Tracker: Start KYC, search CKYCR, switch **Liveness check passed** on, record V-CIP,
complete KYC, advance until `PENDING_REVIEW`, then sign off on the Review tab. The Credit Officer limit
is INR 25 lakh and the Branch Manager limit INR 1 crore, so larger sanctions are refused (DCS-002).

## Status

The domain layer (rules, validators, state machine, dual control, audit chain) has JUnit tests that
pass (24 tests, run with standalone `kotlinc` + JUnit). The Compose UI and Gradle Android build were
written without an Android SDK in the authoring environment and **have not been compiled yet**. Open the
project in Android Studio and run a Gradle sync, then fix any compile issues first.
