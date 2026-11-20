#!/usr/bin/env bash
# Unit-test coverage of the app code: collects execution data through the
# agent-wired test tasks (scripts/jacoco.init.gradle), reports against the
# vanillaDebug classes (the variant the tests run against), and prints the totals.
#
# "App code" = app/src/main classes minus the generated ones (R, BuildConfig, Hilt
# and Room codegen). The JaCoCo CLI is cached in build/jacoco/, reports land in
# build/coverage/ (all gitignored).
set -euo pipefail
cd "$(dirname "$0")/.."

JACOCO_VERSION=0.8.13 # the version Gradle 8.14.5 resolves for its own agent
CLI_JAR=build/jacoco/jacococli.jar
REPORT_DIR=build/coverage
CLASSES_SRC=app/build/intermediates/classes/vanillaDebug/transformVanillaDebugClassesWithAsm/dirs
EXECS=(
    app/build/outputs/unit_test_code_coverage/vanillaDebugUnitTest/testVanillaDebugUnitTest.exec
)

# Execution data. Always goes through the agent-wired test tasks: in build.sh they
# are UP-TO-DATE right after the check (same agent args, instant), and an exec left
# by a run with different agent settings is re-run instead of reported from.
echo "[coverage] Collecting execution data..."
./gradlew --init-script scripts/jacoco.init.gradle \
    :app:testVanillaDebugUnitTest

# The CLI (downloaded once).
if [[ ! -f "$CLI_JAR" ]]; then
    echo "[coverage] Downloading JaCoCo CLI ${JACOCO_VERSION}..."
    mkdir -p build/jacoco
    curl -sfL -o "$CLI_JAR" \
        "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/${JACOCO_VERSION}/org.jacoco.cli-${JACOCO_VERSION}-nodeps.jar"
fi

# App code only: stage the variant classes minus the generated ones.
rm -rf "$REPORT_DIR/classes"
mkdir -p "$REPORT_DIR/classes"
cp -r "$CLASSES_SRC/." "$REPORT_DIR/classes/"
rm -rf "$REPORT_DIR/classes/dagger" "$REPORT_DIR/classes/hilt_aggregated_deps" "$REPORT_DIR/classes/META-INF"
find "$REPORT_DIR/classes" \( \
    -name 'R.class' -o -name 'R$*.class' -o -name 'BuildConfig.class' \
    -o -name 'Dagger*.class' -o -name 'Hilt_*.class' -o -name '*_HiltComponents*.class' \
    -o -name '*Factory.class' -o -name '*Factory$*.class' \
    -o -name '*_Impl.class' -o -name '*_Impl$*.class' \
    -o -name '*_HiltModules$*.class' -o -name '*_ComponentTreeDeps.class' \
    -o -name 'ComposableSingletons$*.class' \
\) -delete

# Report over the unit test execution data.
java -jar "$CLI_JAR" report "${EXECS[@]}" \
    --classfiles "$REPORT_DIR/classes" --name "app-code" \
    --xml "$REPORT_DIR/coverage.xml" --html "$REPORT_DIR/html" --csv "$REPORT_DIR/coverage.csv"

python3 - "$REPORT_DIR/coverage.xml" <<'EOF'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
print("[coverage] App code coverage (app/src/main, generated code excluded):")
for counter in root.findall("counter"):
    missed, covered = int(counter.get("missed")), int(counter.get("covered"))
    total = missed + covered
    print(f"[coverage]   {counter.get('type'):<12} {100.0 * covered / total if total else 0:6.2f}%  ({covered}/{total})")
EOF
echo "[coverage] HTML report: $REPORT_DIR/html/index.html"
