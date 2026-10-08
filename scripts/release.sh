#!/usr/bin/env bash

set -euo pipefail

# ---------------------------------------------------------------------------
# Prerequisites
# GitHub CLI must be installed and authenticated before a release can be made.
# ---------------------------------------------------------------------------

if ! command -v gh >/dev/null 2>&1; then
    echo "GitHub CLI (gh) is not installed. Use 'brew install gh' to add it."
    exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
    echo "GitHub CLI is not authenticated. Please log in with 'gh auth login'"
    exit 1
fi


# ---------------------------------------------------------------------------
# Determine release type and next version
# Bugfix increments the patch version; Maintenance and Feature increment minor.
# ---------------------------------------------------------------------------

CURRENT_VERSION="$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)"

IFS='.' read -r CURRENT_MAJOR CURRENT_MINOR CURRENT_PATCH <<< "$CURRENT_VERSION"

echo
echo "Release type:"
echo "1) Bugfix"
echo "2) Maintenance"
echo "3) Feature"

read -r -p "Select release type [1-3]: " RELEASE_TYPE_SELECTION

case "$RELEASE_TYPE_SELECTION" in
    1)
        RELEASE_TYPE="Bugfix"
        ;;
    2)
        RELEASE_TYPE="Maintenance"
        ;;
    3)
        RELEASE_TYPE="Feature"
        ;;
    *)
        echo "Invalid release type selection."
        exit 1
        ;;
esac

case "$RELEASE_TYPE" in
    "Bugfix")
        SUGGESTED_VERSION="$CURRENT_MAJOR.$CURRENT_MINOR.$((CURRENT_PATCH + 1))"
        ;;
    "Maintenance"|"Feature")
        SUGGESTED_VERSION="$CURRENT_MAJOR.$((CURRENT_MINOR + 1)).0"
        ;;
esac

VERSION="${1:-}"

if [[ -z "$VERSION" ]]; then
    read -r -p "New release version [$SUGGESTED_VERSION] (press ENTER to accept): " VERSION
    VERSION="${VERSION:-$SUGGESTED_VERSION}"
fi

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Invalid version: $VERSION"
    exit 1
fi

TAG="v$VERSION"
RELEASE_TITLE="$TAG - $RELEASE_TYPE Release"
RELEASE_NOTES="docs/release-notes-$VERSION.md"


# ---------------------------------------------------------------------------
# Validate release preparation
# Release notes must already exist and all project changes must be committed.
# ---------------------------------------------------------------------------

if [[ ! -f "$RELEASE_NOTES" ]]; then
    echo "Release notes not found: $RELEASE_NOTES"
    exit 1
fi

if ! git ls-files --error-unmatch "$RELEASE_NOTES" >/dev/null 2>&1; then
    echo "Release notes are not committed: $RELEASE_NOTES"
    exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
    echo "Working tree is not clean. Please commit your changes first."
    exit 1
fi

BRANCH="$(git branch --show-current)"

if [[ "$BRANCH" != "main" ]]; then
    echo "Release must be created from main."
    exit 1
fi

git fetch origin main

if [[ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]]; then
    echo "Local main is not synchronized with origin/main."
    exit 1
fi


# ---------------------------------------------------------------------------
# Prevent duplicate releases and tags before modifying the project.
# ---------------------------------------------------------------------------

if gh release view "$TAG" >/dev/null 2>&1; then
    echo "GitHub release $TAG already exists."
    exit 1
fi

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null 2>&1; then
    echo "Git tag $TAG already exists locally."
    exit 1
fi

if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
    echo "Git tag $TAG already exists on origin."
    exit 1
fi


# ---------------------------------------------------------------------------
# Final confirmation before the script starts modifying the repository.
# ---------------------------------------------------------------------------

read -r -p "Create release \"$RELEASE_TITLE\"? [y/N]: " CONFIRM

if [[ "$CONFIRM" != "y" && "$CONFIRM" != "Y" ]]; then
    echo "Release cancelled."
    exit 0
fi

echo
echo "Preparing release $TAG"
echo


# ---------------------------------------------------------------------------
# Set and verify the Maven version, then run the complete verification build.
# ---------------------------------------------------------------------------

echo "Setting Maven project version..."
mvn versions:set \
    -DnewVersion="$VERSION" \
    -DgenerateBackupPoms=false

ACTUAL_VERSION="$(mvn help:evaluate \
    -Dexpression=project.version \
    -q \
    -DforceStdout)"

if [[ "$ACTUAL_VERSION" != "$VERSION" ]]; then
    echo "Version mismatch: expected $VERSION, got $ACTUAL_VERSION"
    exit 1
fi

echo
echo "Running tests..."
mvn clean verify


# ---------------------------------------------------------------------------
# Commit the release version and publish the corresponding Git tag.
# ---------------------------------------------------------------------------

echo
echo "Committing release version..."
git add pom.xml
git commit -m "Set project version to $VERSION"

echo
echo "Creating tag $TAG..."
git tag -a "$TAG" -m "Release $TAG"

echo
echo "Pushing main..."
git push origin main

echo
echo "Pushing tag..."
git push origin "$TAG"

echo
echo "Tag $TAG created successfully."


# ---------------------------------------------------------------------------
# Create the GitHub Release from the already committed release notes.
# If GitHub fails here, the pushed tag remains valid and can be retried safely.
# ---------------------------------------------------------------------------

echo
echo "Creating GitHub release..."

if ! gh release create "$TAG" --title "$RELEASE_TITLE" --notes-file "$RELEASE_NOTES"; then
    echo
    echo "ERROR: GitHub release could not be created."
    echo "The Git tag $TAG has already been pushed."
    echo
    echo "Please try again later using:"
    echo "gh release create $TAG --title \"$RELEASE_TITLE\" --notes-file \"$RELEASE_NOTES\""
    exit 1
fi

echo
echo "Release $RELEASE_TITLE created successfully."