#!/usr/bin/env bash
# Detects the Android SDK on this machine, verifies it, and writes sdk.dir to local.properties.
#
#   scripts/fix-android-sdk.sh            detect, verify, update local.properties
#   scripts/fix-android-sdk.sh --check    detect and verify only; change nothing
#   scripts/fix-android-sdk.sh --zshrc    also append ANDROID_HOME / PATH to ~/.zshrc (idempotent)
#
# Only the sdk.dir line of local.properties is touched; other lines are preserved.
# Works with macOS's bash 3.2. No Gradle, Kotlin, dependency or source changes.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="$ROOT/local.properties"
CHECK_ONLY=0
WRITE_ZSHRC=0
for a in "$@"; do
  case "$a" in
    --check) CHECK_ONLY=1 ;;
    --zshrc) WRITE_ZSHRC=1 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "Unknown option: $a" >&2; exit 2 ;;
  esac
done

REQUIRED_DIRS="platform-tools platforms build-tools"
COMPILE_SDK=34   # app/build.gradle.kts: compileSdk = 34

is_valid_sdk() {
  local IFS=' '
  local d="$1" r
  [ -n "$d" ] && [ -d "$d" ] || return 1
  for r in $REQUIRED_DIRS; do [ -d "$d/$r" ] || return 1; done
  return 0
}

report_sdk() {
  local IFS=' '
  local d="$1" r
  for r in $REQUIRED_DIRS cmdline-tools; do
    if [ -d "$d/$r" ]; then echo "    [ok]      $r/"; else echo "    [missing] $r/"; fi
  done
  if [ -d "$d/platforms/android-$COMPILE_SDK" ]; then
    echo "    [ok]      platforms/android-$COMPILE_SDK (compileSdk)"
  else
    echo "    [missing] platforms/android-$COMPILE_SDK (compileSdk). Installed: $(ls "$d/platforms" 2>/dev/null | tr '\n' ' ')"
  fi
  if [ -n "$(ls "$d/build-tools" 2>/dev/null)" ]; then
    echo "    [ok]      build-tools: $(ls "$d/build-tools" | tr '\n' ' ')"
  else
    echo "    [missing] build-tools/<version>"
  fi
}

echo "Project: $ROOT"
echo "OS:      $(uname -s)"
echo

# --- 1. what local.properties says now -----------------------------------------------------
CURRENT=""
if [ -f "$PROPS" ]; then
  CURRENT="$(grep -E '^sdk\.dir=' "$PROPS" | tail -1 | cut -d= -f2-)"
  echo "local.properties sdk.dir = ${CURRENT:-<not set>}"
  if [ -n "$CURRENT" ] && ! is_valid_sdk "$CURRENT"; then
    echo "  -> INVALID on this machine (missing directory or required subfolders)."
  fi
else
  echo "local.properties: not found (it is git-ignored; every machine needs its own)"
fi
echo "ANDROID_HOME      = ${ANDROID_HOME:-<unset>}"
echo "ANDROID_SDK_ROOT  = ${ANDROID_SDK_ROOT:-<unset>} (deprecated; ANDROID_HOME is preferred)"
echo

# --- 2. candidates, in priority order -------------------------------------------------------
CANDIDATES="$CURRENT
${ANDROID_HOME:-}
${ANDROID_SDK_ROOT:-}
$HOME/Library/Android/sdk
$HOME/Android/Sdk
/opt/homebrew/share/android-commandlinetools
/usr/local/share/android-commandlinetools
/opt/android-sdk"

FOUND=""
echo "Probing for an Android SDK:"
OLDIFS="$IFS"; IFS='
'
for c in $CANDIDATES; do
  [ -n "$c" ] || continue
  if is_valid_sdk "$c"; then
    echo "  [valid]   $c"
    [ -n "$FOUND" ] || FOUND="$c"
  elif [ -d "$c" ]; then
    echo "  [partial] $c (exists but lacks platform-tools / platforms / build-tools)"
  else
    echo "  [absent]  $c"
  fi
done
IFS="$OLDIFS"
echo

if [ -z "$FOUND" ]; then
  echo "No complete Android SDK found. Nothing was changed."
  echo "Fix: Android Studio > Settings > Languages & Frameworks > Android SDK. Note the"
  echo "'Android SDK Location', install 'Android SDK Platform $COMPILE_SDK' and 'Android SDK Build-Tools',"
  echo "'Android SDK Platform-Tools' (SDK Tools tab), then re-run this script."
  echo "Or pass the path yourself:  echo 'sdk.dir=/your/sdk/path' > local.properties"
  exit 1
fi

echo "Selected SDK: $FOUND"
report_sdk "$FOUND"
echo

# --- 3. write local.properties (sdk.dir line only) -----------------------------------------
if [ "$CHECK_ONLY" -eq 1 ]; then
  echo "--check given: local.properties left unchanged."
elif [ "$CURRENT" = "$FOUND" ]; then
  echo "local.properties already points at the selected SDK; no change needed."
else
  BAK="${TMPDIR:-/tmp}/local.properties.bak.$$"
  [ -f "$PROPS" ] && cp "$PROPS" "$BAK" && echo "Backed up previous file to $BAK"
  TMP="$(mktemp)"
  if [ -f "$PROPS" ]; then grep -v -E '^sdk\.dir=' "$PROPS" > "$TMP"; else : > "$TMP"; fi
  echo "sdk.dir=$FOUND" >> "$TMP"
  cat "$TMP" > "$PROPS"; rm -f "$TMP"
  echo "Updated $PROPS  ->  sdk.dir=$FOUND"
fi
echo

# --- 4. shell environment ------------------------------------------------------------------
SNIPPET="# >>> android sdk (digital-kyc) >>>
export ANDROID_HOME=\"$FOUND\"
export PATH=\"\$PATH:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin\"
# <<< android sdk (digital-kyc) <<<"

if [ "$WRITE_ZSHRC" -eq 1 ]; then
  if grep -q 'android sdk (digital-kyc)' "$HOME/.zshrc" 2>/dev/null; then
    echo "~/.zshrc already contains the block; not appending. Edit it by hand if the path changed."
  else
    printf '\n%s\n' "$SNIPPET" >> "$HOME/.zshrc"
    echo "Appended to ~/.zshrc. Run:  source ~/.zshrc"
  fi
else
  echo "To set the environment for new terminals, add to ~/.zshrc (or re-run with --zshrc):"
  echo
  echo "$SNIPPET"
  echo
  echo "then:  source ~/.zshrc"
fi

if [ -n "${ANDROID_HOME:-}" ] && [ "$ANDROID_HOME" != "$FOUND" ]; then
  echo
  echo "WARNING: current ANDROID_HOME ($ANDROID_HOME) differs from the selected SDK."
  echo "Gradle uses local.properties first, but fix or unset ANDROID_HOME to avoid confusion."
fi
exit 0
