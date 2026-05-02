# STATUS — PLAN FENIX IA (Refactoría 1 Mayo 2026)

## Progreso por Fase

| Fase | Descripción | Estado |
|------|-------------|--------|
| 1 | Materialización Hilt/Room | ✅ Ya existía completa |
| 2 | Pipeline RAG (métrica vectorial) | ✅ Ya existía completa |
| 3 | Sandbox JS persistente | ✅ Ya existía completa |
| 4 | Extractor JSON orquestador | ✅ Ya existía completa |
| 5 | Streaming LLM genuino | ✅ Ya existía completa |
| 6 | Ciclo de vida modelo nativo | ✅ Ya existía completa |
| 7 | WorkManager secuencial OCR | ✅ Ya existía completa |
| 8 | Extractor XLSX streaming SAX | ✅ Ya existía completa |
| 9 | Frenos anti-bucle agentes | ✅ Ya existía completa |
| 10 | TLS fingerprint / motor OkHttp | ✅ CI OK — Sesión 1 |
| 11 | Reducer estado streaming abortado | ✅ Completada — Sesión 2 |
| 12 | Scroll automático Compose | ✅ Completada — Sesión 2 |
| 13 | Auditoría final compilación | ✅ Completada — Sesión 3 |
| 14 | **Loop agéntico tool calling** | ✅ **Completada — Sesión 7** |

---

## Sesión 7 — 2 Mayo 2026

### BUG CRÍTICO DETECTADO EN PRODUCCIÓN — Loop agéntico faltante

**Síntoma (capturas de usuario):**
- El LLM genera correctamente las etiquetas `<tool_call>{"name":"summarize",...}</tool_call>`
- Pero estas etiquetas se **muestran como texto crudo** en el bubble del asistente
- Ninguna herramienta se ejecuta; el usuario nunca ve un resultado

**Causa raíz:**
`ChatViewModel.collectInferenceStream()` (sesiones anteriores) simplemente acumulaba el stream y
al recibir `StreamEvent.Done` persistía el texto tal cual, sin pasar por `ToolCallParser`.
`ToolExecutor` existía y estaba completo pero **nunca era inyectado ni llamado** desde el ViewModel.

**Fix — commit ec2dcdc:**
`ChatViewModel.kt` reescrito con:

1. **`ToolExecutor` inyectado** vía Hilt en el constructor
2. **`runAgenticLoop()`** — bucle hasta `MAX_AGENTIC_ITERATIONS = 5`:
   - Llama al LLM con `collectStreamToString()` y acumula output completo
   - Verifica `ToolCallParser.hasToolCall(output)`
   - Si **no hay tool calls** → persiste como mensaje final y sale
   - Si **hay tool calls** → extrae con `ToolCallParser.extractAll()`, ejecuta cada una vía
     `ToolExecutor.execute()`, inyecta resultados como mensaje "user" en el historial, itera
3. **`collectStreamToString()`** — nuevo helper que:
   - Actualiza `streamingBuffer` en tiempo real con `stripToolCalls(output)` (el usuario ve
     el texto limpio mientras se genera, sin ver las etiquetas internas)
   - Retorna el string completo con tool_calls intactas para que `runAgenticLoop` las procese
   - Si ocurre `StreamEvent.Error` retorna `null` (error ya propagado al uiState)
4. **Feedback visual** durante ejecución: el buffer muestra `"⚙️ Ejecutando N herramienta(s)..."`
   mientras se procesan las tools entre iteraciones

**Flujo resultante (correcto):**
```
Usuario: "¿qué tiene este documento?"
→ LLM genera: <tool_call>{"name":"read_file","args":{"path":"..."}}</tool_call>
→ ViewModel detecta tool call → NO muestra las etiquetas
→ Ejecuta read_file → obtiene JSON con contenido
→ Inyecta resultado en historial como mensaje "user"
→ LLM genera respuesta final con el contenido real del documento
→ Usuario ve: "El documento contiene: ..."
```

**Archivos modificados:**
- `ChatViewModel.kt` — commit ec2dcdc

**Nota para próxima sesión:**
Si el CI falla por el nuevo constructor de `ChatViewModel` (Hilt necesita que `ToolExecutor`
esté en el grafo de DI), verificar que `ToolExecutor` tenga `@Singleton` y `@Inject constructor`
— ya lo tiene desde sesiones anteriores. No debería requerir cambios en módulos de Hilt.

---

## Historial de sesiones anteriores (1–6)
Ver commits: bba6278, a1e28f3, 80b1cfd, 9f14a93, 186eb71, 10209e3, 8ebe868, 4decd9e, 39851b9, aa5e50e
