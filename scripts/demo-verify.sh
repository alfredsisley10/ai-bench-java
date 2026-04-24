#!/usr/bin/env bash
set -euo pipefail

# Interactive demo verification script
# Usage: ./scripts/demo-verify.sh BUG-0001
#
# This script lets you explore a demo bug in the Omnibank banking app:
#   1. Checks out the break branch (bug introduced)
#   2. Runs the relevant tests (expect failure)
#   3. Checks out the fix branch (bug fixed + hidden test)
#   4. Runs the tests again (expect pass)
#   5. Returns to main
#
# The banking app has its own git repo at banking-app/ with separate
# commit history from the benchmarking platform.

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BANKING_APP="$PROJECT_ROOT/banking-app"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

if [ $# -lt 1 ]; then
    echo -e "${YELLOW}Usage:${NC} $0 <BUG-ID>"
    echo ""
    echo "Available bugs:"
    if [ -d "$BANKING_APP/.git" ]; then
        cd "$BANKING_APP"
        git branch --list 'bug/*/break' | sed 's|.*bug/\(.*\)/break|\1|' | sort
    else
        echo "  (banking-app git not initialized)"
    fi
    exit 1
fi

BUG_ID="$1"
BREAK_BRANCH="bug/$BUG_ID/break"
FIX_BRANCH="bug/$BUG_ID/fix"

if [ ! -d "$BANKING_APP/.git" ]; then
    echo -e "${RED}Error:${NC} banking-app/.git not found. Initialize with:"
    echo "  cd banking-app && git init"
    exit 1
fi

cd "$BANKING_APP"

# Verify branches exist
if ! git rev-parse --verify "$BREAK_BRANCH" >/dev/null 2>&1; then
    echo -e "${RED}Error:${NC} Branch '$BREAK_BRANCH' not found."
    echo "Available bug branches:"
    git branch --list 'bug/*/break' | sed 's|.*bug/\(.*\)/break|  \1|' | sort
    exit 1
fi

ORIGINAL_BRANCH=$(git symbolic-ref --short HEAD 2>/dev/null || git rev-parse --short HEAD)

cleanup() {
    echo ""
    echo -e "${BLUE}Returning to $ORIGINAL_BRANCH...${NC}"
    cd "$BANKING_APP"
    git checkout "$ORIGINAL_BRANCH" --quiet 2>/dev/null || true
}
trap cleanup EXIT

echo ""
echo -e "${BLUE}═══════════════════════════════════════════════════${NC}"
echo -e "${BLUE}  Demo verification: $BUG_ID${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════${NC}"

# Show bug metadata if YAML exists
BUG_YAML="$PROJECT_ROOT/bugs/$BUG_ID.yaml"
if [ -f "$BUG_YAML" ]; then
    echo ""
    echo -e "${YELLOW}Bug metadata:${NC}"
    grep -E "^(title|module|difficulty|category):" "$BUG_YAML" | sed 's/^/  /'
fi

# Phase 1: Break branch
echo ""
echo -e "${YELLOW}Phase 1: Checking out break branch ($BREAK_BRANCH)${NC}"
BREAK_COMMIT=$(git rev-parse --short "$BREAK_BRANCH")
echo -e "  Commit: ${RED}$BREAK_COMMIT${NC}"
git checkout "$BREAK_BRANCH" --quiet

echo ""
echo -e "${YELLOW}Running tests (expect failures)...${NC}"
echo ""
if ./gradlew test 2>&1; then
    echo ""
    echo -e "${YELLOW}⚠ Tests passed on break branch — bug may not have a failing test yet.${NC}"
else
    echo ""
    echo -e "${GREEN}✓ Tests failed as expected on the break branch.${NC}"
fi

echo ""
echo -e "${BLUE}───────────────────────────────────────────────────${NC}"
read -p "Press Enter to continue to the fix branch... " -r

# Phase 2: Fix branch
if git rev-parse --verify "$FIX_BRANCH" >/dev/null 2>&1; then
    echo ""
    echo -e "${YELLOW}Phase 2: Checking out fix branch ($FIX_BRANCH)${NC}"
    FIX_COMMIT=$(git rev-parse --short "$FIX_BRANCH")
    echo -e "  Commit: ${GREEN}$FIX_COMMIT${NC}"
    git checkout "$FIX_BRANCH" --quiet

    echo ""
    echo -e "${YELLOW}Showing diff from break to fix:${NC}"
    git diff "$BREAK_BRANCH..$FIX_BRANCH" --stat
    echo ""
    git diff "$BREAK_BRANCH..$FIX_BRANCH"

    echo ""
    echo -e "${YELLOW}Running tests (expect pass)...${NC}"
    echo ""
    if ./gradlew test 2>&1; then
        echo ""
        echo -e "${GREEN}✓ All tests pass on the fix branch.${NC}"
    else
        echo ""
        echo -e "${RED}✗ Tests still failing on fix branch — investigate.${NC}"
    fi
else
    echo ""
    echo -e "${YELLOW}Fix branch ($FIX_BRANCH) not found — skipping fix verification.${NC}"
fi

echo ""
echo -e "${BLUE}═══════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  Demo verification complete for $BUG_ID${NC}"
echo -e "${BLUE}═══════════════════════════════════════════════════${NC}"
