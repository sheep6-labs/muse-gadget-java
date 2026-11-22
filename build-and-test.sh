#!/usr/bin/env bash
# Compile the pure-Java protocol core (Java 8 source level) with noise-java, run the JVM tests and the example.
# No Android needed; JDK (8+) + the pinned noise-java source only.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"  # valid cwd regardless of where we were started (build steps rm -rf build dirs, which may be the caller's cwd)
if [ -n "${JAVA_HOME:-}" ]; then export PATH="$JAVA_HOME/bin:$PATH"; fi

# noise-java (rweather/noise-java, MIT, pure Java), cloned at a pinned commit.
NOISE_DIR="${NOISE_DIR:-$HERE/build-deps/noise-java}"
NOISE_COMMIT=49377b6dfc6a1e75740bce2318118291a57c0d6e
if [ ! -d "$NOISE_DIR/.git" ]; then
  mkdir -p "$NOISE_DIR"
  git clone -q https://github.com/rweather/noise-java.git "$NOISE_DIR"
fi
if [ "$(git -C "$NOISE_DIR" rev-parse HEAD)" != "$NOISE_COMMIT" ]; then
  git -C "$NOISE_DIR" fetch -q origin && git -C "$NOISE_DIR" checkout -q "$NOISE_COMMIT"
fi
# Reproducibility: the checkout must be exactly the pinned commit, with no local modifications or stray files.
if [ -n "$(git -C "$NOISE_DIR" status --porcelain)" ]; then
  echo "error: $NOISE_DIR has local changes; remove it (rm -rf) so it is cloned fresh at $NOISE_COMMIT" >&2
  exit 1
fi

# Official pairing vectors, bundled from the Muse Gadgets SDK (Apache-2.0; see NOTICE).
VECTORS="${VECTORS:-$HERE/tests/vectors/link_pairing_v5.json}"

OUT="$HERE/build"
rm -rf "$OUT"; mkdir -p "$OUT/classes"

echo "[1/3] javac (-source 8 -target 8)"
find "$HERE/src" "$HERE/tests" "$HERE/examples" "$NOISE_DIR/src/main/java" -name '*.java' ! -name package-info.java > "$OUT/sources.txt"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -d "$OUT/classes" @"$OUT/sources.txt"

echo "[2/3] tests"
rc=0
for t in PairingVectorTest NoiseLoopbackTest LinkFramingTest TextTest UploadTest; do
  echo "=== $t ==="
  if [ "$t" = PairingVectorTest ]; then
    java -cp "$OUT/classes" "$t" "$VECTORS" || rc=1
  else
    java -cp "$OUT/classes" "$t" || rc=1
  fi
done
echo "[3/3] example"
echo "=== MinimalExample ==="
java -cp "$OUT/classes" MinimalExample || rc=1
[ "$rc" -eq 0 ] && echo "CORE: ALL TESTS PASSED" || { echo "CORE: FAILURES"; exit 1; }
