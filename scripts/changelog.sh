#!/usr/bin/env bash
# Fold changelog fragments into CHANGELOG.md, cut it, and read its sections without re-rendering the file.
#
#   scripts/changelog.sh fold                     merge every changelog.d/<name>.<section>.md into [Unreleased], then delete them
#   scripts/changelog.sh preview                  print [Unreleased] as it would be after fold, writing nothing
#   scripts/changelog.sh check                    validate fragment names and content
#   scripts/changelog.sh cut <version> [<date>]   fold, then move [Unreleased] under a new "## [<version>] - <date>" heading
#   scripts/changelog.sh notes <version>          print that section's body verbatim (the release notes)
#
# Every other line of the file is left byte-for-byte as it was. CHANGELOG defaults to the
# CHANGELOG.md beside this script's parent directory, CHANGELOG_DIR to changelog.d beside it.
set -euo pipefail

die() {
  echo "changelog.sh: $*" >&2
  exit 1
}

usage() {
  sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 2
}

root="$(cd "$(dirname "$0")/.." && pwd)"
file="${CHANGELOG:-$root/CHANGELOG.md}"
fragdir="${CHANGELOG_DIR:-$root/changelog.d}"
[ -f "$file" ] || die "no changelog at $file"

# Section names in the order CHANGELOG.md lists them; a fragment's <section> is the lowercase form.
sections="Breaking Added Changed Deprecated Removed Fixed Security"

[ $# -ge 1 ] || usage
cmd="$1"
version=""
case "$cmd" in
  fold | preview | check) [ $# -eq 1 ] || usage ;;
  cut | notes)
    [ $# -ge 2 ] || usage
    version="${2#v}"
    [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || die "'$2' is not a SemVer version"
    ;;
  *) usage ;;
esac

# Shared awk: read the file into lines[1..count] and set footer to the first line of the
# trailing block of "[x]: url" link definitions (count + 1 when there is none). A link
# definition inside a section is prose, not footer.
# shellcheck disable=SC2016 # an awk program, expanded by awk
read_file='
  function starts(s, p) { return substr(s, 1, length(p)) == p }
  function islink(s) { return s ~ /^\[[^]]+\]: / }
  function isblank(s) { return s ~ /^[[:space:]]*$/ }
  { lines[++count] = $0 }
  function find_footer(   i) {
    footer = count + 1
    for (i = count; i >= 1 && (isblank(lines[i]) || islink(lines[i])); i--)
      if (islink(lines[i])) footer = i
  }
'

# Body of the section headed "## [<version>]", up to the next "## " heading or the link footer,
# with leading and trailing blank lines dropped. The optional second argument is the file to read.
section() {
  awk -v want="$1" "$read_file"'
    END {
      find_footer()
      h = "## [" want "]"
      for (i = 1; i < footer; i++) {
        if (inside && starts(lines[i], "## ")) break
        if (inside) body[++n] = lines[i]
        else if (lines[i] == h || starts(lines[i], h " ")) { inside = 1; found = 1 }
      }
      if (!found) exit 3
      first = 1; while (first <= n && isblank(body[first])) first++
      last = n;  while (last >= first && isblank(body[last])) last--
      for (i = first; i <= last; i++) print body[i]
    }
  ' "${2:-$file}"
}

# Fragments are changelog.d/<name>.<section>.md holding "- " bullets as they appear in
# CHANGELOG.md. README.md and .gitkeep are not fragments. Fills frag_paths and frag_sections in
# sorted filename order; returns 1 after printing every problem found.
frag_paths=()
frag_sections=()
scan_fragments() {
  local bad=0 path base stem sec s known line seen_bullet
  frag_paths=()
  frag_sections=()
  [ -d "$fragdir" ] || return 0
  while IFS= read -r path; do
    base="${path##*/}"
    case "$base" in README.md | .gitkeep) continue ;; esac
    stem="${base%.md}"
    sec="${stem##*.}"
    known=0
    for s in $sections; do [ "$sec" = "$(tr '[:upper:]' '[:lower:]' <<<"$s")" ] && known=1; done
    if [ "$base" = "$stem" ] || [ "$stem" = "$sec" ] || [[ ! "${stem%.*}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || [ "$known" = 0 ]; then
      echo "changelog.sh: $base: not named <name>.<section>.md with section one of $(tr '[:upper:] ' '[:lower:],' <<<"$sections")" >&2
      bad=1
      continue
    fi
    seen_bullet=0
    local blank=0 problem=0
    while IFS= read -r line || [ -n "$line" ]; do
      if [[ "$line" =~ ^[[:space:]]*$ ]]; then
        blank=1
        continue
      fi
      if [ "$blank" = 1 ] && [ "$seen_bullet" = 1 ]; then
        echo "changelog.sh: $base: blank line between bullet lines" >&2
        problem=1
        break
      fi
      if [[ "$line" == "- "* ]]; then
        seen_bullet=1
      elif [ "$seen_bullet" = 0 ] || [[ "$line" != "  "* ]]; then
        echo "changelog.sh: $base: '$line' is neither a '- ' bullet nor an indented continuation" >&2
        problem=1
        break
      fi
    done <"$path"
    if [ "$problem" = 0 ] && [ "$seen_bullet" = 0 ]; then
      echo "changelog.sh: $base: no '- ' bullet" >&2
      problem=1
    fi
    if [ "$problem" = 1 ]; then
      bad=1
      continue
    fi
    frag_paths+=("$path")
    frag_sections+=("$sec")
  done < <(LC_ALL=C find "$fragdir" -maxdepth 1 -type f | LC_ALL=C sort)
  return "$bad"
}

# Write the folded changelog to $1. Needs scan_fragments to have succeeded with fragments present.
render_fold() {
  local out="$1" bundles i sec
  bundles="$(mktemp -d)"
  for i in "${!frag_paths[@]}"; do
    sec="${frag_sections[$i]}"
    # Leading and trailing blank lines dropped, one trailing newline kept.
    awk 'NF { for (; blanks > 0; blanks--) print ""; print; started = 1; next } started { blanks++ }' \
      "${frag_paths[$i]}" >>"$bundles/$sec"
  done
  grep -qxF '## [Unreleased]' "$file" || {
    rm -rf "$bundles"
    die "no '## [Unreleased]' heading in $file"
  }
  awk -v dir="$bundles" -v order="$sections" "$read_file"'
    END {
      find_footer()
      n = split(order, names, " ")
      for (k = 1; k <= n; k++) rank[names[k]] = k
      us = 0
      for (i = 1; i < footer; i++) if (lines[i] == "## [Unreleased]") { us = i; break }
      ue = footer
      for (i = us + 1; i < footer; i++) if (starts(lines[i], "## ")) { ue = i; break }
      last = us
      for (i = us + 1; i < ue; i++) if (!isblank(lines[i])) last = i
      for (i = us + 1; i < ue; i++) if (starts(lines[i], "### ")) {
        h = substr(lines[i], 5); sub(/[[:space:]]+$/, "", h)
        hl[++nh] = i; hn[nh] = h
      }
      for (k = 1; k <= n; k++) {
        f = dir "/" tolower(names[k])
        text = ""
        while ((getline l < f) > 0) text = text l "\n"
        close(f)
        if (text == "") continue
        found = 0
        for (j = 1; j <= nh; j++) if (hn[j] == names[k]) { found = j; break }
        if (found) {
          e = (found < nh) ? hl[found + 1] : ue
          nl = hl[found]
          for (i = hl[found] + 1; i < e; i++) if (!isblank(lines[i])) nl = i
          after[nl + 1] = after[nl + 1] (nl == hl[found] ? "\n" : "") text
        } else {
          target = 0
          for (j = 1; j <= nh; j++) {
            r = (hn[j] in rank) ? rank[hn[j]] : n + 1
            if (r > k) { target = j; break }
          }
          if (target) before[hl[target]] = before[hl[target]] "### " names[k] "\n\n" text "\n"
          else after[last + 1] = after[last + 1] "\n### " names[k] "\n\n" text
        }
      }
      for (i = 1; i <= count + 1; i++) {
        if (i in after) printf "%s", after[i]
        if (i in before) printf "%s", before[i]
        if (i <= count) print lines[i]
      }
    }
  ' "$file" >"$out"
  rm -rf "$bundles"
}

fold() {
  scan_fragments || die "fix the fragments above; nothing was changed"
  if [ ${#frag_paths[@]} -eq 0 ]; then
    echo "no changelog fragments in $fragdir"
    return 0
  fi
  local tmp
  tmp="$(mktemp "${file}.XXXXXX")"
  # shellcheck disable=SC2064 # expand now: tmp is local to this function
  trap "rm -f '$tmp'" EXIT
  render_fold "$tmp"
  mv "$tmp" "$file"
  trap - EXIT
  rm -f "${frag_paths[@]}"
  echo "folded ${#frag_paths[@]} fragment(s) into [Unreleased] in $file"
}

case "$cmd" in
  notes)
    body="$(section "$version")" || die "no '## [$version]' section in $file"
    [ -n "$body" ] || die "the '## [$version]' section in $file is empty"
    printf '%s\n' "$body"
    ;;

  check)
    scan_fragments || exit 1
    echo "${#frag_paths[@]} changelog fragment(s) ok"
    ;;

  preview)
    scan_fragments || die "fix the fragments above"
    if [ ${#frag_paths[@]} -eq 0 ]; then
      src="$file"
    else
      src="$(mktemp)"
      trap 'rm -f "$src"' EXIT
      render_fold "$src"
    fi
    body="$(section Unreleased "$src")" || die "no '## [Unreleased]' heading in $file"
    printf '## [Unreleased]\n\n%s\n' "$body"
    ;;

  fold)
    fold
    ;;

  cut)
    date="${3:-$(date -u +%Y-%m-%d)}"
    grep -qxF '## [Unreleased]' "$file" || die "no '## [Unreleased]' heading in $file"
    if section "$version" >/dev/null 2>&1; then
      die "'## [$version]' already exists in $file"
    fi
    fold
    # A section holding only "### Added"-style headings has nothing to release.
    unreleased="$(section Unreleased)"
    grep -v -e '^[[:space:]]*$' -e '^#' <<<"$unreleased" >/dev/null ||
      die "[Unreleased] in $file has no entries to release"

    tmp="$(mktemp "${file}.XXXXXX")"
    trap 'rm -f "$tmp"' EXIT
    awk -v v="$version" -v d="$date" "$read_file"'
      END {
        find_footer()
        for (i = 1; i < footer; i++) {
          # The newest released version, for the compare link.
          if (prev == "" && starts(lines[i], "## [") && lines[i] != "## [Unreleased]") {
            prev = substr(lines[i], 5); sub(/\].*/, "", prev)
          }
        }
        for (i = footer; i <= count && base == ""; i++) {
          # Repository base from an existing GitHub link in the footer.
          if (lines[i] ~ /^\[[^]]+\]: https:\/\/github\.com\//) {
            base = lines[i]; sub(/^\[[^]]+\]: /, "", base)
            split(base, p, "/"); base = p[1] "//" p[3] "/" p[4] "/" p[5]
          }
        }
        if (prev == "") link = base "/releases/tag/v" v
        else link = base "/compare/v" prev "...v" v
        added = 0
        for (i = 1; i <= count; i++) {
          line = lines[i]
          if (i < footer && line == "## [Unreleased]") {
            print line; print ""; print "## [" v "] - " d
            continue
          }
          if (i >= footer && base != "" && starts(line, "[Unreleased]: ")) {
            print "[Unreleased]: " base "/compare/v" v "...HEAD"
            print "[" v "]: " link
            added = 1
            continue
          }
          if (i >= footer && base != "" && !added && islink(line)) {
            print "[" v "]: " link
            added = 1
          }
          print line
        }
      }
    ' "$file" >"$tmp"
    mv "$tmp" "$file"
    trap - EXIT
    echo "cut [Unreleased] into [$version] - $date in $file"
    ;;
esac
