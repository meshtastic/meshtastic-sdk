#!/usr/bin/env bash
# Self-contained tests for scripts/changelog.sh against a scratch CHANGELOG and changelog.d.
set -euo pipefail

script="$(cd "$(dirname "$0")" && pwd)/changelog.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
fails=0

fail() {
  echo "FAIL: $*" >&2
  fails=$((fails + 1))
}

# A fresh scratch changelog and an empty fragment directory.
reset() {
  rm -rf "$work/changelog.d"
  mkdir "$work/changelog.d"
  cat >"$work/CHANGELOG.md" <<'EOF'
# Changelog

---

## [Unreleased]

### Added

- Existing added.

### Fixed

- Existing fixed.

## [0.1.0] - 2026-01-01

### Added

- Old.

[Unreleased]: https://github.com/o/r/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/o/r/releases/tag/v0.1.0
EOF
}

run() {
  CHANGELOG="$work/CHANGELOG.md" CHANGELOG_DIR="$work/changelog.d" "$script" "$@"
}

frag() {
  printf '%s\n' "${@:2}" >"$work/changelog.d/$1"
}

expect_file() {
  local name="$1"
  if ! diff -u - "$work/CHANGELOG.md" >"$work/diff.txt"; then
    fail "$name"
    cat "$work/diff.txt" >&2
  fi
}

# fold into existing headings, two fragments in filename order, multi-line bullet intact
reset
frag 20.added.md '- Second added.'
frag 10.added.md '- First added, wrapped' '  onto a second line.'
frag 5.fixed.md '- New fixed.'
run fold >/dev/null
expect_file "fold into existing headings" <<'EOF'
# Changelog

---

## [Unreleased]

### Added

- Existing added.
- First added, wrapped
  onto a second line.
- Second added.

### Fixed

- Existing fixed.
- New fixed.

## [0.1.0] - 2026-01-01

### Added

- Old.

[Unreleased]: https://github.com/o/r/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/o/r/releases/tag/v0.1.0
EOF
[ -z "$(ls "$work/changelog.d")" ] || fail "fold left fragments behind"

# missing headings are created in canonical order (before, between, after existing ones)
reset
frag a.breaking.md '- Broke it.'
frag b.changed.md '- Changed it.'
frag c.security.md '- Secured it.'
run fold >/dev/null
expect_file "missing headings created in order" <<'EOF'
# Changelog

---

## [Unreleased]

### Breaking

- Broke it.

### Added

- Existing added.

### Changed

- Changed it.

### Fixed

- Existing fixed.

### Security

- Secured it.

## [0.1.0] - 2026-01-01

### Added

- Old.

[Unreleased]: https://github.com/o/r/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/o/r/releases/tag/v0.1.0
EOF

# an empty Unreleased gets its headings
reset
printf '# Changelog\n\n## [Unreleased]\n\n## [0.1.0] - 2026-01-01\n\n### Added\n\n- Old.\n' >"$work/CHANGELOG.md"
frag a.added.md '- New.'
run fold >/dev/null
expect_file "empty Unreleased" <<'EOF'
# Changelog

## [Unreleased]

### Added

- New.

## [0.1.0] - 2026-01-01

### Added

- Old.
EOF

# a bad section is rejected and nothing changes
reset
cp "$work/CHANGELOG.md" "$work/orig.md"
frag good.added.md '- Fine.'
frag bad.nonsense.md '- Nope.'
if run fold >/dev/null 2>&1; then fail "bad section accepted by fold"; fi
cmp -s "$work/orig.md" "$work/CHANGELOG.md" || fail "bad fragment changed CHANGELOG.md"
[ -f "$work/changelog.d/good.added.md" ] && [ -f "$work/changelog.d/bad.nonsense.md" ] || fail "bad fragment set was touched"
if run check >/dev/null 2>&1; then fail "check accepted a bad section"; fi

# a fragment with no bullet is rejected
reset
frag a.added.md 'just prose'
if run check >/dev/null 2>&1; then fail "check accepted prose"; fi
reset
frag a.added.md ''
if run fold >/dev/null 2>&1; then fail "fold accepted an empty fragment"; fi

# README.md and .gitkeep are not fragments
reset
printf '# notes\n' >"$work/changelog.d/README.md"
: >"$work/changelog.d/.gitkeep"
run check >/dev/null || fail "check rejected README.md or .gitkeep"

# preview prints the folded section and writes nothing
reset
cp "$work/CHANGELOG.md" "$work/orig.md"
frag a.added.md '- Previewed.'
out="$(run preview)"
grep -qxF -- '- Previewed.' <<<"$out" || fail "preview lacks the fragment bullet"
grep -qF -- 'Old.' <<<"$out" && fail "preview leaked a released section"
cmp -s "$work/orig.md" "$work/CHANGELOG.md" || fail "preview wrote CHANGELOG.md"
[ -f "$work/changelog.d/a.added.md" ] || fail "preview deleted a fragment"

# cut folds first, then cuts
reset
frag a.added.md '- Cut me.'
run cut 0.2.0 2026-02-02 >/dev/null
expect_file "cut folds then cuts" <<'EOF'
# Changelog

---

## [Unreleased]

## [0.2.0] - 2026-02-02

### Added

- Existing added.
- Cut me.

### Fixed

- Existing fixed.

## [0.1.0] - 2026-01-01

### Added

- Old.

[Unreleased]: https://github.com/o/r/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/o/r/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/o/r/releases/tag/v0.1.0
EOF
[ -z "$(ls "$work/changelog.d")" ] || fail "cut left fragments behind"
run notes 0.2.0 | grep -qxF -- '- Cut me.' || fail "notes lacks the folded bullet"

if [ "$fails" -ne 0 ]; then
  echo "changelog_test.sh: $fails failure(s)" >&2
  exit 1
fi
echo "changelog_test.sh: all passed"
