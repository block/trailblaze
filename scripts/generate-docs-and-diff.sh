#!/bin/bash
set -o xtrace
set -o errexit
set -o pipefail
set -o nounset

# Verify we're in the correct git repository
if ! git rev-parse --git-dir > /dev/null 2>&1; then
    echo "Error: Not in a git repository"
    exit 1
fi

GENERATED_DOCS_FOLDER_RELATIVE_PATH="docs/generated"
mkdir -p $GENERATED_DOCS_FOLDER_RELATIVE_PATH
echo "GENERATED_DOCS_FOLDER_RELATIVE_PATH=$GENERATED_DOCS_FOLDER_RELATIVE_PATH"

GENERATED_DOCS_FOLDER="$(realpath $GENERATED_DOCS_FOLDER_RELATIVE_PATH)"
echo "GENERATED_DOCS_FOLDER=$GENERATED_DOCS_FOLDER"

# Safely remove the docs/generated directory if it exists
if [ -d "$GENERATED_DOCS_FOLDER" ]; then
    echo "Removing existing $GENERATED_DOCS_FOLDER directory..."
    rm -rf "$GENERATED_DOCS_FOLDER"
fi

# Generate docs
./gradlew :docs:generator:run

# Verify the contents of `GENERATED_DOCS_FOLDER` have not caused any diffs
git diff --exit-code $GENERATED_DOCS_FOLDER_RELATIVE_PATH

# If there are diffs, print the diffs and exit with a non-zero exit code
if [ $? -ne 0 ]; then
    echo "Error: Documentation changes detected!"
    echo "Please run './gradlew :docs:generator:run' to regenerate the docs"
    echo "and commit the changes to $GENERATED_DOCS_FOLDER"
    exit 1
else
    echo "✓ No documentation changes detected"
fi 