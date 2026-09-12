#!/usr/bin/env bash
#
# create-github-project.sh
# -----------------------------------------------------------------------------
# Creates GitHub milestones, issues, labels and a Project (v2) board from the
# synthX remediation plan (docs/remediation-plan.json).
#
# One milestone per phase, one issue per task. The script is IDEMPOTENT: every
# creation step first checks whether the object already exists (milestone by
# exact title, issue by exact title, label by name) and skips it if so, so it is
# safe to re-run after editing the plan or after a partial failure.
#
# PREREQUISITES (external tools, must be on PATH):
#   - gh   : GitHub CLI, authenticated (`gh auth login`). Needs repo scope, and
#            `project` scope for the Projects v2 steps (`gh auth refresh -s project`).
#   - jq   : JSON processor, used to read the plan file.
# Environment: written for Git Bash / MSYS on Windows (standard coreutils).
#   NOTE for MSYS: paths in `gh api` calls are protected with MSYS2_ARG_CONV_EXCL
#   so that Git Bash does not rewrite `repos/owner/repo/milestones` into a
#   Windows path. Do not remove those.
#
# USAGE
#   ./scripts/create-github-project.sh [options]
#
#   --repo <owner/repo>     Target repository (default: $REPO or b1ackwall/synthX)
#   --plan <path>           Plan JSON (default: docs/remediation-plan.json)
#   --project-number <n>    Existing Projects v2 number to add issues to.
#                           If omitted, a project titled "synthX Remediation" is
#                           looked up for the repo owner and created if absent.
#   --project-title <s>     Project title to look up/create (default: synthX Remediation)
#   --no-project            Skip all Projects v2 work (milestones + issues only)
#   --dry-run               Print everything that WOULD be created. Makes no
#                           mutating gh calls at all.
#   -h | --help             Show this help
#
#   Run from the repository root (the default plan path is relative to it).
# -----------------------------------------------------------------------------

set -euo pipefail

# --- Configuration -----------------------------------------------------------

PLAN_FILE="docs/remediation-plan.json"          # relative to repo root
DEFAULT_REPO="b1ackwall/synthX"
PROJECT_TITLE="synthX Remediation"

REPO="${REPO:-$DEFAULT_REPO}"                    # overridable by env or --repo
PROJECT_NUMBER=""
DRY_RUN=false
SKIP_PROJECT=false

# --- Argument parsing --------------------------------------------------------

usage() {
    sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --repo)            REPO="${2:?--repo needs a value}"; shift 2 ;;
        --plan)            PLAN_FILE="${2:?--plan needs a value}"; shift 2 ;;
        --project-number)  PROJECT_NUMBER="${2:?--project-number needs a value}"; shift 2 ;;
        --project-title)   PROJECT_TITLE="${2:?--project-title needs a value}"; shift 2 ;;
        --no-project)      SKIP_PROJECT=true; shift ;;
        --dry-run)         DRY_RUN=true; shift ;;
        -h|--help)         usage 0 ;;
        *) echo "Unknown option: $1" >&2; usage 1 ;;
    esac
done

# --- Prerequisite checks -----------------------------------------------------

missing_tool=false
for tool in gh jq; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "ERROR: required tool '$tool' was not found on PATH." >&2
        case "$tool" in
            gh) echo "       Install the GitHub CLI: https://cli.github.com/" >&2 ;;
            jq) echo "       Install jq: https://jqlang.github.io/jq/download/" >&2 ;;
        esac
        missing_tool=true
    fi
done
$missing_tool && { echo "Aborting: install the missing prerequisites and re-run." >&2; exit 127; }

if [[ ! -f "$PLAN_FILE" ]]; then
    echo "ERROR: plan file not found: $PLAN_FILE" >&2
    echo "       Run this script from the repository root, or pass --plan <path>." >&2
    exit 1
fi

if ! jq empty "$PLAN_FILE" >/dev/null 2>&1; then
    echo "ERROR: plan file is not valid JSON: $PLAN_FILE" >&2
    exit 1
fi

if [[ ! "$REPO" =~ ^[^/]+/[^/]+$ ]]; then
    echo "ERROR: --repo must be in owner/repo form (got: '$REPO')." >&2
    exit 1
fi

# Auth is only needed for real runs; a dry run should work offline.
if ! $DRY_RUN; then
    if ! gh auth status >/dev/null 2>&1; then
        echo "ERROR: gh is not authenticated. Run: gh auth login" >&2
        exit 1
    fi
fi

REPO_OWNER="${REPO%%/*}"

# Stop MSYS/Git Bash from converting gh api endpoint paths into Windows paths.
export MSYS2_ARG_CONV_EXCL="*"

# --- Counters for the final summary ------------------------------------------

MILESTONES_CREATED=0
MILESTONES_SKIPPED=0
ISSUES_CREATED=0
ISSUES_SKIPPED=0
LABELS_CREATED=0
LABELS_SKIPPED=0
PROJECT_ITEMS_ADDED=0
PROJECT_STATUS="not used"

echo "=============================================================="
echo " synthX remediation -> GitHub"
echo "=============================================================="
echo " Repository : $REPO"
echo " Plan file  : $PLAN_FILE"
echo " Mode       : $($DRY_RUN && echo 'DRY RUN (no mutations)' || echo 'LIVE')"
echo "=============================================================="
echo

# --- Step 1: labels ----------------------------------------------------------
# Every label used by any task must exist before an issue references it, or
# `gh issue create --label` fails. Colours are assigned by severity; area and
# other tag labels get a neutral colour.

label_color() {
    case "$1" in
        severity:critical) echo "b60205" ;;  # deep red
        severity:high)     echo "d93f0b" ;;  # orange-red
        severity:medium)   echo "fbca04" ;;  # yellow
        severity:low)      echo "cfd3d7" ;;  # grey
        area:*)            echo "0e8a16" ;;  # green
        *)                 echo "ededed" ;;  # default light grey
    esac
}

label_description() {
    case "$1" in
        severity:critical) echo "Critical - data loss or a user-facing lie about a destructive action" ;;
        severity:high)     echo "High - correctness or recoverability defect" ;;
        severity:medium)   echo "Medium - defect with a workaround or limited blast radius" ;;
        severity:low)      echo "Low - polish, cosmetics or defence-in-depth" ;;
        area:*)            echo "Subsystem: ${1#area:}" ;;
        *)                 echo "From the 2026-09 synthX audit" ;;
    esac
}

echo "--- Step 1/4: labels ---"

# Collect the distinct set of labels used anywhere in the plan.
mapfile -t PLAN_LABELS < <(jq -r '[.phases[].tasks[].labels[]] | unique[]' "$PLAN_FILE")

# Existing labels in the repo (empty list on a dry run without auth).
EXISTING_LABELS=""
if ! $DRY_RUN; then
    EXISTING_LABELS="$(gh label list --repo "$REPO" --limit 200 --json name --jq '.[].name' 2>/dev/null || true)"
fi

for label in "${PLAN_LABELS[@]}"; do
    if grep -Fxq "$label" <<<"$EXISTING_LABELS"; then
        echo "  = label exists : $label"
        LABELS_SKIPPED=$((LABELS_SKIPPED + 1))
        continue
    fi
    color="$(label_color "$label")"
    desc="$(label_description "$label")"
    if $DRY_RUN; then
        echo "  + would create label: $label (#$color) - $desc"
    else
        gh label create "$label" --repo "$REPO" --color "$color" --description "$desc" >/dev/null
        echo "  + created label: $label (#$color)"
    fi
    LABELS_CREATED=$((LABELS_CREATED + 1))
done
echo

# --- Step 2: milestones ------------------------------------------------------
# One milestone per phase, titled "Phase {n}: {name}", described by the phase
# goal. Existing milestones are listed once and matched by exact title so a
# re-run never creates a duplicate.

echo "--- Step 2/4: milestones (one per phase) ---"

EXISTING_MILESTONES_JSON="[]"
if ! $DRY_RUN; then
    # state=all so a closed milestone is still recognised rather than duplicated.
    EXISTING_MILESTONES_JSON="$(gh api --paginate "repos/$REPO/milestones?state=all&per_page=100" 2>/dev/null || echo '[]')"
fi

# Maps milestone title -> milestone number, for the issue step below.
declare -A MILESTONE_NUMBER_BY_TITLE=()

phase_count="$(jq '.phases | length' "$PLAN_FILE")"
for (( pi = 0; pi < phase_count; pi++ )); do
    phase_number="$(jq -r ".phases[$pi].phase_number" "$PLAN_FILE")"
    phase_name="$(jq -r ".phases[$pi].name" "$PLAN_FILE")"
    phase_goal="$(jq -r ".phases[$pi].goal" "$PLAN_FILE")"
    milestone_title="Phase ${phase_number}: ${phase_name}"

    existing_number="$(jq -r --arg t "$milestone_title" \
        'if type=="array" then (map(select(.title == $t)) | .[0].number // empty) else empty end' \
        <<<"$EXISTING_MILESTONES_JSON")"

    if [[ -n "$existing_number" ]]; then
        echo "  = milestone exists : $milestone_title (#$existing_number)"
        MILESTONE_NUMBER_BY_TITLE["$milestone_title"]="$existing_number"
        MILESTONES_SKIPPED=$((MILESTONES_SKIPPED + 1))
        continue
    fi

    if $DRY_RUN; then
        echo "  + would create milestone: $milestone_title"
        echo "      description: $(cut -c1-100 <<<"$phase_goal")..."
        MILESTONE_NUMBER_BY_TITLE["$milestone_title"]="DRYRUN"
    else
        new_number="$(gh api --method POST "repos/$REPO/milestones" \
            -f "title=$milestone_title" \
            -f "description=$phase_goal" \
            --jq '.number')"
        echo "  + created milestone: $milestone_title (#$new_number)"
        MILESTONE_NUMBER_BY_TITLE["$milestone_title"]="$new_number"
    fi
    MILESTONES_CREATED=$((MILESTONES_CREATED + 1))
done
echo

# --- Step 3: issues ----------------------------------------------------------
# One issue per task, titled "[P{phase}] {task title}". The body is the task's
# markdown body (with its "### Done when" section lifted out), followed by a
# rendered "Bundled findings" list and a rendered "Acceptance criteria" list.
#
# Existing issues are matched by exact title via `gh issue list --search`, which
# is a fuzzy search, so the exact-title check is done locally with jq afterwards.

echo "--- Step 3/4: issues (one per task) ---"

# Collected so the project step can add them without re-querying per task.
ISSUE_URLS=()

issue_exists_url() {
    # $1 = exact issue title. Echoes the issue URL if one exists, else nothing.
    local title="$1"
    gh issue list --repo "$REPO" --state all --limit 100 \
        --search "\"$title\" in:title" --json title,url 2>/dev/null \
        | jq -r --arg t "$title" 'map(select(.title == $t)) | .[0].url // empty'
}

for (( pi = 0; pi < phase_count; pi++ )); do
    phase_number="$(jq -r ".phases[$pi].phase_number" "$PLAN_FILE")"
    phase_name="$(jq -r ".phases[$pi].name" "$PLAN_FILE")"
    milestone_title="Phase ${phase_number}: ${phase_name}"
    task_count="$(jq ".phases[$pi].tasks | length" "$PLAN_FILE")"

    echo "  Phase $phase_number - $phase_name ($task_count tasks)"

    for (( ti = 0; ti < task_count; ti++ )); do
        task_json="$(jq -c ".phases[$pi].tasks[$ti]" "$PLAN_FILE")"

        task_id="$(jq -r '.task_id' <<<"$task_json")"
        task_title="$(jq -r '.title' <<<"$task_json")"
        issue_title="[P${phase_number}] ${task_title}"

        # Skip early if an issue with this exact title already exists.
        if ! $DRY_RUN; then
            found_url="$(issue_exists_url "$issue_title")"
            if [[ -n "$found_url" ]]; then
                echo "    = issue exists : $issue_title"
                echo "                     $found_url"
                ISSUE_URLS+=("$found_url")
                ISSUES_SKIPPED=$((ISSUES_SKIPPED + 1))
                continue
            fi
        fi

        # Split the stored body on its "### Done when" marker: everything before
        # it is the narrative, everything after becomes the acceptance criteria.
        body_main="$(jq -r '.body | split("### Done when")[0]' <<<"$task_json")"
        criteria="$(jq -r '.body | (split("### Done when") | if length > 1 then .[1] else "" end)' <<<"$task_json")"

        # Render the bundled findings with a pointer back to the source report.
        findings_md="$(jq -r '
            def report_name:
              if   startswith("R1-") then "Round 1 - Data Layer & Migrations (docs/audit-round-01-data-layer.md)"
              elif startswith("R2-") then "Round 2 - File/SAF & Permissions (docs/audit-round-02-file-permissions.md)"
              elif startswith("R3-") then "Round 3 - Playback Engine (docs/audit-round-03-playback-engine.md)"
              elif startswith("R4-") then "Round 4 - Delete/Replace Flows (docs/audit-round-04-delete-replace-flows.md)"
              elif startswith("R5-") then "Round 5 - Background Rendering (docs/audit-round-05-background-rendering.md)"
              elif startswith("R6-") then "Round 6 - Playlist Art (docs/audit-round-06-playlist-art.md)"
              elif startswith("R7-") then "Round 7 - Onboarding & Recovery (docs/audit-round-07-onboarding-recovery.md)"
              elif startswith("R8-") then "Round 8 - Backup/Restore & Remaining UI (docs/audit-round-08-backup-restore-and-remaining-ui.md)"
              else "unknown report" end;
            .findings | map("- `" + . + "` - " + report_name) | join("\n")
        ' <<<"$task_json")"

        files_md="$(jq -r '.affected_files | map("- `" + . + "`") | join("\n")' <<<"$task_json")"
        severity="$(jq -r '.severity' <<<"$task_json")"

        # Assemble the final issue body.
        issue_body="$(cat <<EOF
> **Task \`${task_id}\`** &middot; phase ${phase_number} (${phase_name}) &middot; severity \`${severity}\`
> Generated from \`${PLAN_FILE}\`; see \`docs/remediation-plan.md\` for the full plan.

${body_main}
### Bundled findings

${findings_md}

### Affected files

${files_md}

### Acceptance criteria
${criteria}
EOF
)"

        # Labels: repeat --label once per entry.
        label_args=()
        while IFS= read -r label; do
            [[ -n "$label" ]] && label_args+=(--label "$label")
        done < <(jq -r '.labels[]' <<<"$task_json")

        milestone_number="${MILESTONE_NUMBER_BY_TITLE[$milestone_title]:-}"

        if $DRY_RUN; then
            echo "    + would create issue: $issue_title"
            echo "        task      : $task_id"
            echo "        severity  : $severity"
            echo "        labels    : $(jq -r '.labels | join(", ")' <<<"$task_json")"
            echo "        milestone : $milestone_title"
            echo "        findings  : $(jq -r '.findings | join(", ")' <<<"$task_json")"
            echo "        files     : $(jq -r '.affected_files | length' <<<"$task_json") file(s)"
            echo "        body      : $(wc -c <<<"$issue_body" | tr -d ' ') bytes"
            ISSUES_CREATED=$((ISSUES_CREATED + 1))
            continue
        fi

        # `gh issue create` takes the milestone by title (or number).
        new_url="$(gh issue create \
            --repo "$REPO" \
            --title "$issue_title" \
            --body "$issue_body" \
            --milestone "$milestone_title" \
            "${label_args[@]}")"

        echo "    + created issue: $issue_title"
        echo "                     $new_url"
        ISSUE_URLS+=("$new_url")
        ISSUES_CREATED=$((ISSUES_CREATED + 1))
    done
done
echo

# --- Step 4: Projects (v2) ---------------------------------------------------
# Resolve a project number: use --project-number if given, otherwise find a
# project titled "$PROJECT_TITLE" owned by the repo owner, creating it if
# absent. Then add every issue (created or pre-existing) as a project item.
# `gh project item-add` is idempotent for an issue already on the board.

echo "--- Step 4/4: GitHub Project (v2) ---"

if $SKIP_PROJECT; then
    echo "  (skipped: --no-project)"
    PROJECT_STATUS="skipped (--no-project)"
elif $DRY_RUN; then
    if [[ -n "$PROJECT_NUMBER" ]]; then
        echo "  would use existing project #$PROJECT_NUMBER (owner: $REPO_OWNER)"
        PROJECT_STATUS="#$PROJECT_NUMBER (dry run)"
    else
        echo "  would look up project titled \"$PROJECT_TITLE\" for owner $REPO_OWNER"
        echo "  would create it if absent, then add $ISSUES_CREATED issue(s) to it"
        PROJECT_STATUS="\"$PROJECT_TITLE\" (dry run, lookup-or-create)"
    fi
else
    if [[ -z "$PROJECT_NUMBER" ]]; then
        # Look the project up by title among the owner's projects.
        PROJECT_NUMBER="$(gh project list --owner "$REPO_OWNER" --limit 100 --format json 2>/dev/null \
            | jq -r --arg t "$PROJECT_TITLE" '.projects // [] | map(select(.title == $t)) | .[0].number // empty')"

        if [[ -z "$PROJECT_NUMBER" ]]; then
            echo "  no project titled \"$PROJECT_TITLE\" - creating it"
            PROJECT_NUMBER="$(gh project create --owner "$REPO_OWNER" --title "$PROJECT_TITLE" --format json \
                | jq -r '.number')"
            echo "  + created project #$PROJECT_NUMBER (\"$PROJECT_TITLE\")"
            PROJECT_STATUS="#$PROJECT_NUMBER \"$PROJECT_TITLE\" (created)"
        else
            echo "  = using existing project #$PROJECT_NUMBER (\"$PROJECT_TITLE\")"
            PROJECT_STATUS="#$PROJECT_NUMBER \"$PROJECT_TITLE\" (existing)"
        fi
    else
        echo "  = using project #$PROJECT_NUMBER (from --project-number)"
        PROJECT_STATUS="#$PROJECT_NUMBER (supplied)"
    fi

    for url in "${ISSUE_URLS[@]}"; do
        if gh project item-add "$PROJECT_NUMBER" --owner "$REPO_OWNER" --url "$url" >/dev/null 2>&1; then
            PROJECT_ITEMS_ADDED=$((PROJECT_ITEMS_ADDED + 1))
        else
            echo "  ! could not add to project: $url" >&2
            echo "    (if this says the scope is missing, run: gh auth refresh -s project)" >&2
        fi
    done
    echo "  added $PROJECT_ITEMS_ADDED item(s) to project #$PROJECT_NUMBER"
fi
echo

# --- Summary -----------------------------------------------------------------

echo "=============================================================="
echo " Summary$($DRY_RUN && echo ' (DRY RUN - nothing was created)' || echo '')"
echo "=============================================================="
printf "  Labels     : %3d created, %3d already existed\n" "$LABELS_CREATED"     "$LABELS_SKIPPED"
printf "  Milestones : %3d created, %3d already existed\n" "$MILESTONES_CREATED" "$MILESTONES_SKIPPED"
printf "  Issues     : %3d created, %3d already existed\n" "$ISSUES_CREATED"     "$ISSUES_SKIPPED"
printf "  Project    : %s\n" "$PROJECT_STATUS"
if ! $DRY_RUN && ! $SKIP_PROJECT; then
    printf "  Project items added: %d\n" "$PROJECT_ITEMS_ADDED"
fi
echo "=============================================================="
if $DRY_RUN; then
    echo "Re-run without --dry-run to apply against $REPO."
fi
