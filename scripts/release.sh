#!/usr/bin/env bash

set -euo pipefail

VERSION="${1:-}"

if [[ -z "$VERSION" ]]; then
    read -r -p "New release version (e.g. 1.3.0): " VERSION
fi

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Invalid version: $VERSION"
    exit 1
fi

TAG="v$VERSION"

if [[ -n "$(git status --porcelain)" ]]; then
    echo "Working tree is not clean."
    exit 1
fi

BRANCH="$(git branch --show-current)"

if [[ "$BRANCH" != "main" ]]; then
    echo "Release must be created from main."
    exit 1
fi

if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "Tag $TAG already exists."
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
echo "Release $TAG created successfully."