#!/usr/bin/env bash
# =============================================================================
# FENIX IA — Validación NODO-12: Compuertas 1, 2, 4 y 5 (sin dispositivo ADB)
# Ejecutar desde la raíz del proyecto: bash scripts/validate_nodo12.sh
# =============================================================================

set -euo pipefail

PASS=0
FAIL=0
ERRORS=()

check() {
  local label="$1"
  local result="$2"
  if [ "$result" = "PASS" ]; then
    echo "  ✅ $label"
    ((PASS++))
  else
    echo "  ❌ $label — $result"
    ((FAIL++))
    ERRORS+=("$label")
  fi
}

echo ""
echo "══════════════════════════════════════════════════════"
echo " FENIX IA — NODO-12 · Pipeline de Calidad (sin ADB)"
echo "══════════════════════════════════════════════════════"

# ─────────────────────────────────────────────────────────
# COMPUERTA 1: Cristalización de Requisitos
# ─────────────────────────────────────────────────────────
echo ""
echo "┌─ COMPUERTA 1: Requisitos ──────────────────────────"

if [ -f "AGENTS.md" ]; then
  PROHIBIDO_COUNT=$(grep -c "PROHIBIDO" AGENTS.md 2>/dev/null || true)
  RAM_LIMIT=$(grep -c "RAM_IDLE_LIMIT" AGENTS.md 2>/dev/null || true)

  [ "$PROHIBIDO_COUNT" -ge 10 ] \
    && check "AGENTS.md restricciones presentes ($PROHIBIDO_COUNT)" "PASS" \
    || check "AGENTS.md restricciones presentes" "FAIL: solo $PROHIBIDO_COUNT instancias PROHIBIDO"

  [ "$RAM_LIMIT" -ge 1 ] \
    && check "RAM_IDLE_LIMIT definido en AGENTS.md" "PASS" \
    || check "RAM_IDLE_LIMIT definido en AGENTS.md" "FAIL: no encontrado"
else
  check "AGENTS.md existe" "FAIL: archivo no encontrado"
fi

# ─────────────────────────────────────────────────────────
# COMPUERTA 2: Análisis Estático
# ─────────────────────────────────────────────────────────
echo ""
echo "┌─ COMPUERTA 2: Análisis Estático ───────────────────"

# ktlint
if command -v ./gradlew &>/dev/null; then
  KTLINT_OUT=$(./gradlew ktlintCheck 2>&1 || true)
  if echo "$KTLINT_OUT" | grep -q "ktlint reported"; then
    check "ktlint: sin errores de formato" "FAIL: ver output de ktlint"
  else
    check "ktlint: sin errores de formato" "PASS"
  fi

  # detekt
  DETEKT_OUT=$(./gradlew detekt 2>&1 || true)
  if echo "$DETEKT_OUT" | grep -qE "^FAILURE|errors$"; then
    check "detekt: sin code smells críticos" "FAIL: ver output de detekt"
  else
    check "detekt: sin code smells críticos" "PASS"
  fi
else
  check "gradlew disponible" "FAIL: ./gradlew no encontrado — ejecutar desde raíz del proyecto"
fi

# ─────────────────────────────────────────────────────────
# COMPUERTA 4: Compilación y Ensamblado
# ─────────────────────────────────────────────────────────
echo ""
echo "┌─ COMPUERTA 4: Compilación assembleDebug ────────────"

if command -v ./gradlew &>/dev/null; then
  BUILD_OUT=$(./gradlew assembleDebug 2>&1)
  BUILD_CODE=$?
  if [ $BUILD_CODE -eq 0 ]; then
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
    [ -f "$APK_PATH" ] \
      && check "assembleDebug → APK generado" "PASS" \
      || check "assembleDebug → APK generado" "FAIL: APK no encontrado en $APK_PATH"
  else
    echo "  Output relevante:"
    echo "$BUILD_OUT" | grep -E "error:|ERROR" | head -20 | sed 's/^/    /'
    check "assembleDebug exit 0" "FAIL: exit code $BUILD_CODE"
  fi
else
  check "gradlew disponible para build" "FAIL: ./gradlew no encontrado"
fi

# ─────────────────────────────────────────────────────────
# COMPUERTA 5: Auditoría de Seguridad
# ─────────────────────────────────────────────────────────
echo ""
echo "┌─ COMPUERTA 5: Auditoría de Seguridad ──────────────"

SRC="app/src/main/java"

# API keys en texto plano
KEY_HITS=$(grep -r "AIza\|sk-\|gsk_\|Bearer \|api_key\s*=\s*\"" "$SRC" --include="*.kt" 2>/dev/null \
  | grep -v "//" || true)
[ -z "$KEY_HITS" ] \
  && check "Sin API keys hardcodeadas en código fuente" "PASS" \
  || check "Sin API keys hardcodeadas" "FAIL: encontradas → $KEY_HITS"

# SharedPreferences
SP_HITS=$(grep -r "SharedPreferences\|getSharedPreferences" "$SRC" --include="*.kt" 2>/dev/null \
  | grep -v "//" || true)
[ -z "$SP_HITS" ] \
  && check "Sin uso de SharedPreferences (usar DataStore)" "PASS" \
  || check "Sin uso de SharedPreferences" "FAIL: → $SP_HITS"

# DCL ilegal
DCL_HITS=$(grep -r "DexClassLoader\|PathClassLoader" "$SRC" --include="*.kt" 2>/dev/null \
  | grep -v "//" || true)
[ -z "$DCL_HITS" ] \
  && check "Sin DCL ilegal (DexClassLoader/PathClassLoader)" "PASS" \
  || check "Sin DCL ilegal" "FAIL: → $DCL_HITS"

# Flutter / React Native
FLUTTER_HITS=$(grep -r "flutter\|ReactNative\|react-native" app/build.gradle.kts 2>/dev/null || true)
[ -z "$FLUTTER_HITS" ] \
  && check "Sin frameworks prohibidos (Flutter/RN)" "PASS" \
  || check "Sin frameworks prohibidos" "FAIL: → $FLUTTER_HITS"

# bitmap.recycle() en todos los métodos OCR/bitmap
BITMAP_USES=$(grep -rn "BitmapFactory\.decode\|createBitmap" "$SRC" --include="*.kt" 2>/dev/null \
  | grep -v "//" | wc -l || true)
RECYCLE_COUNT=$(grep -rn "\.recycle()" "$SRC" --include="*.kt" 2>/dev/null \
  | grep -v "//" | wc -l || true)

echo "  ℹ️  bitmap.recycle() calls: $RECYCLE_COUNT | BitmapFactory.decode uses: $BITMAP_USES"
[ "$RECYCLE_COUNT" -ge 1 ] \
  && check "bitmap.recycle() presente en código fuente" "PASS" \
  || check "bitmap.recycle() presente" "FAIL: no encontrado — revisar R-06"

# ─────────────────────────────────────────────────────────
# COMPUERTA 3 (parcial): Tests unitarios sin dispositivo
# ─────────────────────────────────────────────────────────
echo ""
echo "┌─ COMPUERTA 3: Tests Unitarios ─────────────────────"

if command -v ./gradlew &>/dev/null; then
  TEST_OUT=$(./gradlew testDebugUnitTest 2>&1)
  TEST_CODE=$?
  FAILED_TESTS=$(echo "$TEST_OUT" | grep -c "FAILED" || true)
  PASSED_TESTS=$(echo "$TEST_OUT" | grep -c "PASSED" || true)

  echo "  ℹ️  Tests PASSED: $PASSED_TESTS | FAILED: $FAILED_TESTS"

  if [ $TEST_CODE -eq 0 ] && [ "$FAILED_TESTS" -eq 0 ]; then
    check "Todos los tests unitarios PASS (compuerta 3)" "PASS"
  else
    echo "$TEST_OUT" | grep "FAILED" | head -10 | sed 's/^/    /'
    check "Tests unitarios sin fallos" "FAIL: $FAILED_TESTS tests fallidos"
  fi

  # Cobertura Jacoco (si está disponible)
  COV_REPORT="app/build/reports/jacoco/testDebugUnitTest/jacocoTestReport.xml"
  if [ -f "$COV_REPORT" ]; then
    COVERAGE=$(python3 - <<'EOF'
import xml.etree.ElementTree as ET, sys
try:
    tree = ET.parse('app/build/reports/jacoco/testDebugUnitTest/jacocoTestReport.xml')
    counters = {c.get('type'): c for c in tree.getroot().findall('.//counter')}
    b = counters.get('BRANCH')
    if b:
        covered = int(b.get('covered', 0))
        missed = int(b.get('missed', 0))
        total = covered + missed
        pct = (covered / total * 100) if total > 0 else 0
        print(f"{pct:.1f}")
    else:
        print("N/A")
except Exception as e:
    print("N/A")
EOF
)
    if [ "$COVERAGE" != "N/A" ]; then
      PCT=$(echo "$COVERAGE" | cut -d. -f1)
      [ "$PCT" -ge 85 ] \
        && check "Cobertura de ramas ≥ 85% (${COVERAGE}%)" "PASS" \
        || check "Cobertura de ramas ≥ 85%" "FAIL: ${COVERAGE}% < 85%"
    else
      echo "  ℹ️  Reporte Jacoco no disponible — ejecutar con: ./gradlew testDebugUnitTest jacocoTestReport"
    fi
  fi
else
  check "gradlew disponible para tests" "FAIL: ./gradlew no encontrado"
fi

# ─────────────────────────────────────────────────────────
# RESUMEN FINAL
# ─────────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════════════"
TOTAL=$((PASS + FAIL))
echo " RESULTADO: $PASS/$TOTAL compuertas PASS"

if [ $FAIL -eq 0 ]; then
  echo " ✅ TODAS LAS COMPUERTAS SUPERADAS — listo para dispositivo"
  echo " Próximo paso: conectar Samsung A10/Xiaomi C14 y ejecutar:"
  echo "   ./gradlew connectedDebugAndroidTest"
else
  echo " ❌ COMPUERTAS FALLIDAS ($FAIL):"
  for err in "${ERRORS[@]}"; do
    echo "   - $err"
  done
  echo " → Corregir antes de continuar al NODO-13/14 en dispositivo"
fi
echo "══════════════════════════════════════════════════════"
echo ""

[ $FAIL -eq 0 ] && exit 0 || exit 1
