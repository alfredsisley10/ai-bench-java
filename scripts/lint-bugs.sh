#!/usr/bin/env bash
# Lint bug YAML files for leaks of production class/file/method names.
#
# Severity:
#   HIGH  - FILENAME_LEAK, CLASS_LEAK, TEST_METHOD_LEAK
#   MEDIUM - PACKAGE_PATH_LEAK
#
# Usage: scripts/lint-bugs.sh [bugs/BUG-*.yaml ...]
#        (defaults to bugs/BUG-*.yaml relative to repo root)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUGS_DIR="$REPO_ROOT/bugs"

FILES=("${@:-$BUGS_DIR/BUG-*.yaml}")
# Expand glob if default
if [[ ${#FILES[@]} -eq 1 && "${FILES[0]}" == *"*"* ]]; then
  mapfile -t FILES < <(ls "$BUGS_DIR"/BUG-*.yaml 2>/dev/null)
fi

total_bugs=0
flagged=0
high_count=0
medium_count=0
info_count=0

# Returns 1 if $1 (needle token) appears as a token in $2 (haystack).
# "Token" = surrounded by non-alphanumeric boundaries or start/end of string.
token_match() {
  local needle="$1"
  local haystack="$2"
  # Use grep with word-constituent chars; treat CamelCase identifiers as whole tokens
  # Split haystack on non-alphanumeric chars and check for exact match.
  echo "$haystack" | grep -qP "(?<![A-Za-z0-9_])${needle}(?![A-Za-z0-9_])"
}

# Returns 1 if $1 (needle) appears as substring anywhere in $2 (haystack).
substr_match() {
  local needle="$1"
  local haystack="$2"
  [[ "$haystack" == *"$needle"* ]]
}

lint_file() {
  local yaml_file="$1"
  local bug_id
  bug_id=$(basename "$yaml_file" .yaml)

  # ---- parse fields from YAML ----
  # problemStatement: everything under the key until next top-level key
  local problem_statement
  problem_statement=$(awk '/^problemStatement:/{found=1; sub(/^problemStatement:\s*\|?\s*/,""); if($0!="") print $0; next} found && /^[a-zA-Z]/{found=0} found{print}' "$yaml_file" | sed 's/^  //')

  # hints: array items (lines starting with "  - ")
  local hints
  hints=$(awk '/^hints:/{found=1;next} found && /^[a-zA-Z]/{found=0} found && /^  - /{sub(/^  - /,""); print}' "$yaml_file")

  # combined text to check
  local text_to_check="$problem_statement"$'\n'"$hints"

  # filesTouched paths
  local files_touched
  mapfile -t files_touched < <(awk '/^filesTouched:/{found=1;next} found && /^[a-zA-Z]/{found=0} found && /^  - /{sub(/^  - /,""); print}' "$yaml_file")

  # hiddenTest class and method
  local hidden_class hidden_method
  hidden_class=$(awk '/^hiddenTest:/{found=1;next} found && /^[a-zA-Z]/{found=0} found && /class:/{gsub(/.*class:\s*/,""); print; exit}' "$yaml_file")
  hidden_method=$(awk '/^hiddenTest:/{found=1;next} found && /^[a-zA-Z]/{found=0} found && /method:/{gsub(/.*method:\s*/,""); print; exit}' "$yaml_file")

  local findings=()

  # FILENAME_LEAK (HIGH): basename with and without extension as token
  for ft in "${files_touched[@]}"; do
    local bn_ext bn_no_ext
    bn_ext=$(basename "$ft")
    bn_no_ext="${bn_ext%.*}"

    if token_match "$bn_no_ext" "$text_to_check"; then
      findings+=("HIGH|FILENAME_LEAK|token '$bn_no_ext' (from $bn_ext)")
    fi
  done

  # CLASS_LEAK (HIGH): simple class name minus 'Test' suffix as token
  if [[ -n "$hidden_class" ]]; then
    local simple_class class_minus_test
    simple_class="${hidden_class##*.}"
    # Remove trailing 'Test' suffix if present
    if [[ "$simple_class" == *Test ]]; then
      class_minus_test="${simple_class%Test}"
    else
      class_minus_test="$simple_class"
    fi
    if token_match "$class_minus_test" "$text_to_check"; then
      findings+=("HIGH|CLASS_LEAK|token '$class_minus_test' (from class $simple_class)")
    fi
  fi

  # TEST_METHOD_LEAK (HIGH): hiddenTestMethod anywhere as substring
  if [[ -n "$hidden_method" ]]; then
    if substr_match "$hidden_method" "$text_to_check"; then
      findings+=("HIGH|TEST_METHOD_LEAK|substring '$hidden_method'")
    fi
  fi

  # PACKAGE_PATH_LEAK (MEDIUM): rightmost two path segments of any filesTouched as substring
  for ft in "${files_touched[@]}"; do
    # rightmost two segments: e.g. ach/AchCutoffPolicy.java
    local two_seg
    two_seg=$(echo "$ft" | awk -F/ '{print $(NF-1)"/"$NF}')
    if substr_match "$two_seg" "$text_to_check"; then
      findings+=("MEDIUM|PACKAGE_PATH_LEAK|substring '$two_seg'")
    fi
  done

  total_bugs=$((total_bugs + 1))
  if [[ ${#findings[@]} -gt 0 ]]; then
    flagged=$((flagged + 1))
    echo "=== $bug_id ==="
    for f in "${findings[@]}"; do
      local sev rule detail
      IFS='|' read -r sev rule detail <<< "$f"
      echo "  [$sev] $rule: $detail"
      case "$sev" in
        HIGH)   high_count=$((high_count + 1)) ;;
        MEDIUM) medium_count=$((medium_count + 1)) ;;
        INFO)   info_count=$((info_count + 1)) ;;
      esac
    done
  fi
}

for f in "${FILES[@]}"; do
  lint_file "$f"
done

echo ""
echo "=== SUMMARY ==="
echo "totalBugs:   $total_bugs"
echo "flagged:     $flagged"
echo "highCount:   $high_count"
echo "mediumCount: $medium_count"
echo "infoCount:   $info_count"

if [[ $high_count -gt 0 ]]; then
  exit 1
fi
exit 0
