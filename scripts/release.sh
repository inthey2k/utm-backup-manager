#!/usr/bin/env bash

set -euo pipefail

if ! command -v gh >/dev/null 2>&1; then
    echo "GitHub CLI (gh) is not installed. Use 'brew install gh' to add it."
    exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
    echo "GitHub CLI is not authenticated."
    exit 1
fi

VERSION="${1:-}"

if [[ -z "$VERSION" ]]; then
    read -r -p "New release version (e.g. 1.3.0): " VERSION
fi

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Invalid version: $VERSION"
    exit 1
fi

TAG="v$VERSION"

RELEASE_NOTES="docs/release-notes-$VERSION.md"

if [[ ! -f "$RELEASE_NOTES" ]]; then
    echo "Release notes not found: $RELEASE_NOTES"
    exit 1
fi
if ! git ls-files --error-unmatch "$RELEASE_NOTES" >/dev/null 2>&1; then
    echo "Release notes are not committed: $RELEASE_NOTES"
    exit 1
fi

git fetch origin main
if [[ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]]; then
    echo "Local main is not synchronized with origin/main."
    exit 1
fi
read -r -p "Create release $TAG? [y/N]: " CONFIRM

if [[ "$CONFIRM" != "y" && "$CONFIRM" != "Y" ]]; then
    echo "Release cancelled."
    exit 0
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

echo
echo "Preparing release $TAG"
echo

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
echo
echo
echo "Creating GitHub release..."

if ! gh release create "$TAG" --title "$TAG" --notes-file "$RELEASE_NOTES"; then
    echo
    echo "ERROR: GitHub release could not be created."
    echo "The Git tag $TAG has already been pushed."
    echo
    echo "Please try again later using:"
    echo "gh release create $TAG --title \"$TAG\" --notes-file \"$RELEASE_NOTES\""
    exit 1
fi

echo
echo "Release $TAG created successfully."

echo
echo "Release v$VERSION created successfully."