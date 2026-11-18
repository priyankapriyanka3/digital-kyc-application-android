# Device and emulator testing

## What runs without a device (verified)

```bash
./gradlew assembleDebug                 # debug APK
./gradlew assembleDebugAndroidTest      # instrumented-test APK (compiles; not run)
./gradlew testDebugUnitTest             # 25 domain tests + 3 Robolectric UI smoke tests
./gradlew lintDebug                     # Android lint
```

The Robolectric smoke test (`app/src/test/.../AppSmokeTest.kt`) launches the real
`MainActivity` on the JVM and walks all three screens through the dual-control sign-off
and the audit-chain tamper check. It is not a substitute for a device run: it has no real
GPU, no real ML Kit OCR and no real permissions or photo picker.

## Running on a physical device (recommended)

1. Enable Developer options and USB debugging on a phone running Android 8.0 (API 26) or newer.
2. Connect it and accept the RSA prompt, then check `adb devices -l` shows `device`, not `unauthorized`.
3. Install and launch:
   ```bash
   ./gradlew installDebug
   adb shell am start -n com.sapphirebank.kyc/.MainActivity
   ```
4. Run the instrumented smoke test (same journey as the Robolectric test):
   ```bash
   ./gradlew connectedDebugAndroidTest
   ```
5. Watch for crashes: `adb logcat -s AndroidRuntime:E ActivityManager:W`.

## Running on an emulator

Needs a host with hardware virtualization (KVM, HAXM/WHPX or Apple Hypervisor).
Android Studio, Device Manager, create a Pixel device with a Google APIs system image
(API 34, arm64-v8a on Apple Silicon, x86_64 elsewhere), then run `connectedDebugAndroidTest`.

## Manual end-to-end checklist

1. **Application tab:** tap *Fill sample*, then *Submit application*. Expect `ARN-000001`,
   Aadhaar shown as `XXXX XXXX 0124`.
2. **Tracker tab:** *Start KYC*, *Search CKYCR*, switch *Liveness check passed* on,
   *Record V-CIP session*, *Complete KYC*, then *Advance from ...* until `PENDING_REVIEW`.
3. **Review tab:** Approve as Credit Officer, then as Branch Manager. *Open account* appears.
   *Verify chain* shows "Chain intact"; *Simulate tamper* then *Verify chain* shows "Hash mismatch at #1".
4. Also try: submit with a bad PAN (KYC-001 error), switch the V-CIP liveness off and complete KYC
   (application is rejected), tick PEP (Senior Management sign-off becomes required).
5. Scan a PAN/Aadhaar photo from the gallery to exercise ML Kit OCR (not covered by any automated test).

## Authoring-environment blockers (why no device run was done)

The environment used to build this project is a 2-vCPU, 4 GB aarch64 Alpine (musl) VM:

| Blocker | Detail |
|---|---|
| No `/dev/kvm` | No hardware virtualization, so only software emulation (QEMU TCG) is possible. |
| Google emulator unavailable | The SDK `emulator` package has no Linux-arm64 host build. |
| SDK `adb`/`aapt2` are x86-64 | Worked around: Alpine's native `android-tools` adb, and `aapt2` under `qemu-x86_64` (see `~/.gradle/gradle.properties` in that VM). |
| Plain QEMU cannot run the ranchu image fully | The API 34 `aosp_atd` arm64 image booted to Android init under QEMU (kernel, first-stage mount, `/data`, adbd and zygote started) but `surfaceflinger`, `zygote` and the sensors HAL crash-looped. Probable (not verified) cause: the image expects the Android Emulator's goldfish-pipe / gfxstream host services, which plain QEMU does not provide. No tombstone was captured. `adb` saw the guest only as `offline`, which may instead be an auth problem: the guest's `adb_keys` copy failed because the emulator normally injects `qemu.adb.pubkey`. Pre-seeding `/data/misc/adb/adb_keys` was not tried. |

No app install, launch or instrumented test was executed on any device or emulator.
