#!/usr/bin/env python3
import pathlib
import subprocess
import sys

# Constants
REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
FOSSA_DIR = REPO_ROOT / ".fossa"
POM_FILE = FOSSA_DIR / "pom.xml"
FOSSA_YAML_FILE = FOSSA_DIR / "fossa.yml"
PATTERN = "**/dependencies/*RuntimeClasspath.txt"
GRADLEW_PATH = REPO_ROOT / "gradlew"

def run_gradle_dependency_guard():
    print("🔄 Running './gradlew dependencyGuardBaseline' to update dependency lock files...")
    result = subprocess.run([str(GRADLEW_PATH), "dependencyGuardBaseline"], cwd=REPO_ROOT)
    if result.returncode != 0:
        print("❌ Gradle task failed. Aborting.")
        sys.exit(result.returncode)
    print("✅ Gradle task completed.")

def find_dependency_files():
    return list(REPO_ROOT.glob(PATTERN))

def parse_dependencies(files):
    deps = set()
    for file in files:
        with open(file, "r") as f:
            for line in f:
                dep = line.strip()
                if dep and not dep.startswith("#") and dep.count(":") == 2:
                    deps.add(dep)
    return sorted(deps)

def generate_pom_xml(dependencies):
    print(f"📝 Generating synthetic pom.xml with {len(dependencies)} dependencies...")
    FOSSA_DIR.mkdir(parents=True, exist_ok=True)

    with open(POM_FILE, "w") as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write('<project xmlns="http://maven.apache.org/POM/4.0.0"\n')
        f.write('         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"\n')
        f.write('         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0\n')
        f.write('                             http://maven.apache.org/xsd/maven-4.0.0.xsd">\n')
        f.write('  <modelVersion>4.0.0</modelVersion>\n')
        f.write('  <groupId>com.trailblaze</groupId>\n')
        f.write('  <artifactId>trailblaze-gradle-dependencies</artifactId>\n')
        f.write('  <version>1.0.0</version>\n')
        f.write('  <dependencies>\n')

        for dep in dependencies:
            group, artifact, version = dep.split(":")
            f.write("    <dependency>\n")
            f.write(f"      <groupId>{group}</groupId>\n")
            f.write(f"      <artifactId>{artifact}</artifactId>\n")
            f.write(f"      <version>{version}</version>\n")
            f.write("    </dependency>\n")

        f.write("  </dependencies>\n")
        f.write("</project>\n")

    print(f"✅ Wrote {POM_FILE.relative_to(REPO_ROOT)}")

def generate_fossa_yaml():
    print("📝 Generating .fossa/fossa.yml...")
    with open(FOSSA_YAML_FILE, "w") as f:
        f.write("version: 3\n")
        f.write("projects:\n")
        f.write("  - id: trailblaze-gradle-dependencies\n")
        f.write("    path: .\n")
        f.write("    type: gradle\n")
    print(f"✅ Wrote {FOSSA_YAML_FILE.relative_to(REPO_ROOT)}")

def run_fossa_analyze():
    print("🚀 Running 'fossa analyze' in .fossa/...")
    result = subprocess.run(["fossa", "analyze"], cwd=FOSSA_DIR)
    if result.returncode != 0:
        print("❌ FOSSA analysis failed.")
        sys.exit(result.returncode)
    print("✅ FOSSA analysis completed.")

def main():
    run_gradle_dependency_guard()

    print("🔍 Searching for dependency lock files...")
    files = find_dependency_files()
    if not files:
        print("⚠️ No matching dependency files found.")
        return

    print(f"📂 Found {len(files)} dependency file(s).")
    dependencies = parse_dependencies(files)
    print(f"📚 Parsed {len(dependencies)} unique dependencies.")

    generate_pom_xml(dependencies)
    generate_fossa_yaml()
    run_fossa_analyze()

if __name__ == "__main__":
    main()
