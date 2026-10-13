#!/usr/bin/env bash
# Builds a self-contained app image (bundled Java runtime) and installs it
# for the current user with an application-menu entry.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$ROOT/packaging/linux"
DEST="${XDG_DATA_HOME:-$HOME/.local/share}"
OPT="$HOME/.local/opt"
APP_ID="ua.medicine"
NAME="Medicine"

cd "$ROOT"
mvn -q -DskipTests package dependency:copy-dependencies \
    -DincludeScope=runtime -DoutputDirectory=target/libs

STAGE="target/jpackage"
rm -rf "$STAGE"
mkdir -p "$STAGE/input/fx" "$STAGE/out"
cp target/medicine-*.jar "$STAGE/input/medicine.jar"
for jar in target/libs/*.jar; do
    case "$(basename "$jar")" in
        javafx-*-linux.jar) cp "$jar" "$STAGE/input/fx/" ;;
        javafx-*) ;; # platform-less stubs, not needed
        *) cp "$jar" "$STAGE/input/" ;;
    esac
done

jpackage --type app-image \
    --name "$NAME" \
    --dest "$STAGE/out" \
    --input "$STAGE/input" \
    --main-jar medicine.jar \
    --main-class ua.medicine.App \
    --icon "$HERE/medicine.png" \
    --add-modules java.se,jdk.httpserver,jdk.unsupported,jdk.localedata,jdk.zipfs,jdk.net,jdk.charsets,jdk.crypto.cryptoki,jdk.security.auth,jdk.management \
    --java-options '--module-path $APPDIR/fx' \
    --java-options '--add-modules javafx.controls,javafx.fxml' \
    --java-options '--enable-native-access=ALL-UNNAMED,javafx.graphics'

mkdir -p "$OPT"
rm -rf "$OPT/$NAME"
cp -r "$STAGE/out/$NAME" "$OPT/$NAME"

install -Dm644 "$HERE/medicine.svg" "$DEST/icons/hicolor/scalable/apps/$APP_ID.svg"
install -Dm644 "$HERE/medicine.png" "$DEST/icons/hicolor/256x256/apps/$APP_ID.png"
install -Dm644 "$HERE/medicine-512.png" "$DEST/icons/hicolor/512x512/apps/$APP_ID.png"

install -d "$DEST/applications"
cat > "$DEST/applications/$APP_ID.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Version=1.5
Name=Ліки родини
Name[en]=Family Medicine
GenericName=Планувальник прийому ліків
Comment=Розклад і контроль прийому ліків для всієї родини
Comment[en]=Plan and track medicine intake for the whole family
Exec="$OPT/$NAME/bin/$NAME"
Icon=$APP_ID
Terminal=false
Categories=Office;Calendar;
Keywords=ліки;таблетки;нагадування;medicine;pills;reminder;
StartupNotify=true
StartupWMClass=ua.medicine.App
DESKTOP

gtk-update-icon-cache -q -t "$DEST/icons/hicolor" 2>/dev/null || true
update-desktop-database -q "$DEST/applications" 2>/dev/null || true
echo "Installed: $OPT/$NAME  (menu entry: $DEST/applications/$APP_ID.desktop)"
