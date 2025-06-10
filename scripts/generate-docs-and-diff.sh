#!/bin/bash
set -euo pipefail

# Verify we're in the correct git repository
if ! git rev-parse --git-dir > /dev/null 2>&1; then
    echo "Error: Not in a git repository"
    exit 1
fi

# Verify we're in the trailblaze repository
if ! git remote -v | grep -q "trailblaze"; then
    echo "Error: Not in the trailblaze repository"
    exit 1
fi

# Safely remove the docs/generated directory if it exists
if [ -d "docs/generated" ]; then
    echo "Removing existing docs/generated directory..."
    rm -rf docs/generated
fi

# Generate docs
./gradlew :docs-generator:run

# Verify the contents of `docs/generated` have not caused any diffs
git diff --exit-code docs/generated

# If there are diffs, print the diffs and exit with a non-zero exit code
if [ $? -ne 0 ]; then
    echo "Error: Documentation changes detected!"
    echo "Please run './gradlew :docs-generator:run' to regenerate the docs"
    echo "and commit the changes to docs/generated/"
    exit 1
else
    echo "✓ No documentation changes detected"
fi 