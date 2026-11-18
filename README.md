# Sapphire Bank Digital KYC (Android)

Role-based KYC and account-opening app. Kotlin, Jetpack Compose, Material 3, Clean Architecture (domain / data / presentation).
Business rules come from the Sapphire Bank KYC knowledge base (rule catalogue KYC-001..009, document rules DOC-001..004, state machine, dual control DCS-001..006, audit rules AUD-001/002).

The app has **no backend**. Data is in memory and seeded with fictional customers at start-up. Sign-in is a demo role picker with no passwords.

## Six role workspaces

| Role | Home | What they do |
|---|---|---|
| **Customer** | Welcome, products, application progress | Open Savings / Current / FD-RD / Retail Loan through a step-by-step wizard, upload PAN, masked Aadhaar, address proof, photograph and financial documents, track status, answer information requests, see the welcome kit. DICGC insurance information is shown for FD/RD. |
| **KYC Analyst** | Assigned cases with filters | Document preview, name / DOB / address validation results, KYC rules, V-CIP session, approve KYC, reject KYC or request information. |
| **Credit Officer** | Eligibility review queue | Income, business proof, bureau score gauge, DTI, eligibility outcome, recommend approve / request documents / decline. |
| **Branch Manager** | Pipeline dashboard (pending / in review / approved today) | Final decision with mandatory justification, reassign applications to analysts, reports. |
| **Compliance Officer** | AML / PEP alerts | Risk score, country risk, watchlist match, screening evidence, clear or place on hold, audit trail. Read-only financial data. |
| **System Admin** | System configuration | Users and roles, KYC thresholds, products, country risk, reference data, configuration log. Cannot see or approve individual applications. |

## Business rules (enforced in the service layer, not only in the UI)

All checks live in `domain/usecase/CaseService.kt` and `domain/rules/*`. The UI hides actions a role cannot use, but the service re-resolves the caller from the user directory on every call and refuses anything the role is not permitted to do.

- Savings accounts are auto-approved after KYC, unless the case is flagged, on hold, a PEP, or has an open request.
- PEP and high-risk cases need Compliance clearance and Senior Management sign-off (DCS-004).
- Loans require a Credit Officer recommendation before the Branch Manager.
- An application with an open information request cannot be approved or rejected.
- Aadhaar is vaulted at intake: only `XXXXXXXX` plus the last four digits is stored or displayed.
- Branch Manager (L2/L3) decisions and all rejections need a written justification. It is part of the signed hash.
- Segregation of duties (DCS-003): the creator of an application, the sourcing RM and the V-CIP official can never approve it.
- System Admin has configuration permissions only. Compliance has no approval permission.
- Audit history is append-only and hash-chained (AUD-002). The repository rejects any save that changes or drops existing audit events, approvals or documents.

## Architecture

```
app/src/main/java/com/sapphirebank/kyc
├── domain/
│   ├── model/        roles, permissions, case aggregate, documents, config, screening, eligibility
│   ├── rules/        KycRuleEngine, DualControlEngine, ScreeningEngine (+ eligibility, auto-approval),
│   │                 RiskEngine, AuditChain, ApplicationStateMachine, AccessPolicy
│   ├── usecase/      CaseWorkflow (pure transitions), CaseService (authorization boundary)
│   ├── validation/   PAN / Aadhaar (Verhoeff) validators, masking, OCR extraction
│   └── repository/   CaseRepository, DocumentTextRecognizer, CreditBureau (interfaces)
├── data/             InMemoryCaseRepository (append-only), SeedData, SimulatedBureau, MlKitTextRecognizer
└── presentation/
    ├── theme/        Tokens (8dp spacing, radius, colours), Material 3 theme
    ├── common/       reusable components (status chips, metric cards, score gauge, document cards,
    │                 validation card, workflow tracker, audit timeline, decision sheet, search/filter, states)
    ├── customer/ staff/ admin/   role screens and ViewModels
    └── AppRoot, SignIn, WorkspaceViewModel   shell, adaptive navigation (bottom bar / rail), notifications
```

## Simulated or illustrative parts (read before relying on this)

- **Authentication** is a demo role picker. Production needs real authentication and server-side enforcement; `CaseService` is written to move behind a server unchanged.
- **Credit bureau** (`SimulatedBureau`) returns fixed or pseudo scores. **CKYCR**, **Aadhaar OTP e-KYC** and **AML screening lists** are simulated. The watchlist and country risk list are fictional configuration.
- **Risk scoring weights, eligibility thresholds (DTI, bureau) and DICGC cover** are illustrative, because the knowledge base does not publish them. They are editable in the System Admin screens or `EligibilityPolicy` / `RiskEngine`.
- All names, PANs and Aadhaar numbers in `SeedData` are invented (the Aadhaar numbers only satisfy the Verhoeff checksum).

## Build and test

Requirements: JDK 17, Android SDK 34, a `local.properties` with `sdk.dir` (see `scripts/fix-android-sdk.sh`).

```bash
./gradlew assembleDebug                 # debug APK
./gradlew testDebugUnitTest             # 55 domain/service tests + 9 Robolectric UI smoke tests
./gradlew assembleDebugAndroidTest      # compiles the instrumented tests
./gradlew lintDebug
./gradlew installDebug                  # needs a device or emulator
./gradlew connectedDebugAndroidTest     # needs a device or emulator
```

## Test status (honest summary)

- Unit tests (domain rules, role enforcement, seed scenarios) and Robolectric UI smoke tests pass on the JVM.
- The Robolectric tests render the real screens but are **not a device test**: no real GPU, no photo picker, no ML Kit OCR.
- The instrumented tests (`app/src/androidTest`) **compile but have not been run** on any device or emulator. See `docs/DEVICE_TESTING.md`.
- Not covered by any test: ML Kit OCR on a real image, runtime permissions, performance and memory profiling, TalkBack walkthroughs.
