#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SOURCE_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
REYNARD_DIR="$SOURCE_DIR/Natives/external/Reynard"
FIREFOX_DIR="$REYNARD_DIR/engine/firefox"
PATCH_DIR="$REYNARD_DIR/patches"
BUILD_ROOT="${REYNARD_BUILD_ROOT:-$SOURCE_DIR/Natives/build/reynard}"
DERIVED_DATA="$BUILD_ROOT/DerivedData"
CONFIGURATION="${REYNARD_CONFIGURATION:-Release}"
FIREFOX_URL="https://github.com/mozilla-firefox/firefox.git"
FIREFOX_TAG="$(tr -d '\000\r\n ' < "$REYNARD_DIR/engine/release.txt")"

if [ "$(uname -s)" != "Darwin" ]; then
    echo "Reynard/GeckoView requires macOS and Xcode." >&2
    exit 1
fi

for command in git python3 rustup xcodebuild; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "Missing Reynard build dependency: $command" >&2
        exit 1
    fi
done

if [ ! -d "$FIREFOX_DIR/.git" ]; then
    if [ -d "$FIREFOX_DIR" ] && [ -n "$(ls -A "$FIREFOX_DIR" 2>/dev/null)" ]; then
        echo "$FIREFOX_DIR exists but is not a Git checkout; refusing to overwrite it." >&2
        exit 1
    fi
    mkdir -p "$(dirname "$FIREFOX_DIR")"
    git clone --filter=blob:none --depth 1 --branch "$FIREFOX_TAG" \
        "$FIREFOX_URL" "$FIREFOX_DIR"
fi

git -C "$FIREFOX_DIR" sparse-checkout disable >/dev/null 2>&1 || true
git -C "$FIREFOX_DIR" fetch --depth 1 origin "refs/tags/$FIREFOX_TAG"

expected_commit="$(git -C "$FIREFOX_DIR" rev-parse "FETCH_HEAD^{commit}")"
actual_commit="$(git -C "$FIREFOX_DIR" rev-parse HEAD)"
if [ "$actual_commit" != "$expected_commit" ]; then
    if [ -n "$(git -C "$FIREFOX_DIR" status --porcelain)" ]; then
        echo "Firefox commit mismatch with local changes: expected $expected_commit, got $actual_commit" >&2
        exit 1
    fi
    git -C "$FIREFOX_DIR" checkout --detach "$expected_commit"
fi

patch_list="$BUILD_ROOT/reynard-patches.txt"
mkdir -p "$BUILD_ROOT"
find "$PATCH_DIR" -type f -name '*.patch' -print | LC_ALL=C sort > "$patch_list"

# Patch-by-patch probing makes a partially prepared checkout safe to resume.
# It also preserves local work: no reset, clean or forced checkout is used.
while IFS= read -r patch_file; do
    patch_name="${patch_file#"$PATCH_DIR/"}"
    if git -C "$FIREFOX_DIR" apply --check --whitespace=nowarn "$patch_file"; then
        echo "Applying Reynard patch $patch_name"
        git -C "$FIREFOX_DIR" apply --3way --whitespace=nowarn "$patch_file"
    elif git -C "$FIREFOX_DIR" apply --check --reverse --whitespace=nowarn "$patch_file"; then
        echo "Reynard patch already applied: $patch_name"
    else
        echo "Firefox changes conflict with Reynard patch: $patch_file" >&2
        exit 1
    fi
done < "$patch_list"

REYNARD_CONFIGURATION="$CONFIGURATION" \
    "$REYNARD_DIR/tools/development/build-gecko.sh" --auto-clobber

xcodebuild \
    -project "$REYNARD_DIR/browser/Reynard.xcodeproj" \
    -target GeckoView \
    -target "Reynard Helper" \
    -configuration "$CONFIGURATION" \
    -sdk iphoneos \
    -destination 'generic/platform=iOS' \
    SYMROOT="$DERIVED_DATA/Build/Products" \
    OBJROOT="$DERIVED_DATA/Build/Intermediates.noindex" \
    CODE_SIGNING_ALLOWED=NO \
    CODE_SIGNING_REQUIRED=NO \
    DEVELOPMENT_TEAM= \
    build

PRODUCTS="$DERIVED_DATA/Build/Products/$CONFIGURATION-iphoneos"
FRAMEWORK="$PRODUCTS/GeckoView.framework"
HELPER="$PRODUCTS/Reynard Helper.appex"
if [ ! -d "$FRAMEWORK" ] || [ ! -d "$HELPER" ]; then
    echo "Reynard products are incomplete under $PRODUCTS" >&2
    exit 1
fi

rm -rf "$BUILD_ROOT/GeckoView.framework" "$BUILD_ROOT/Reynard Helper.appex"
ditto "$FRAMEWORK" "$BUILD_ROOT/GeckoView.framework"
ditto "$HELPER" "$BUILD_ROOT/Reynard Helper.appex"
echo "Reynard GPU browser products are ready under $BUILD_ROOT"
