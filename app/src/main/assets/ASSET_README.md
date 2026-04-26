# ASSET README — Modelos TFLite para FENIX IA

## Modelo requerido: MiniLM-L6-v2 cuantizado

El archivo `minilm_l6_v2_quantized.tflite` (~22 MB) **no está incluido** en el repositorio
por su tamaño. Debe colocarse manualmente en:

```
app/src/main/assets/minilm_l6_v2_quantized.tflite
```

## Cómo obtener el modelo

### Opción 1 — Descargar preconvertido (recomendado)
```bash
# Desde Hugging Face Hub (modelo optimizado para Android, int8)
wget -O app/src/main/assets/minilm_l6_v2_quantized.tflite \
  https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model_int8.onnx
```
> ⚠️ Nota: necesitarás convertir el ONNX a TFLite. Ver Opción 2.

### Opción 2 — Convertir con TFLite Model Maker (Python)
```bash
pip install tflite-model-maker tensorflow
```

```python
# convert_minilm.py
from sentence_transformers import SentenceTransformer
import tensorflow as tf
import numpy as np

model = SentenceTransformer('all-MiniLM-L6-v2')

# Exportar como TFLite con cuantización int8
converter = tf.lite.TFLiteConverter.from_saved_model('minilm_saved')
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_types = [tf.int8]
tflite_model = converter.convert()

with open('minilm_l6_v2_quantized.tflite', 'wb') as f:
    f.write(tflite_model)
```

### Opción 3 — Usar embedding remoto (fallback sin TFLite)
Si no tienes el modelo TFLite disponible en desarrollo, puedes usar la implementación
alternativa basada en hash para tests:

```kotlin
// En di/EmbeddingModule.kt — cambiar la implementación inyectada durante desarrollo:
@Provides
@Singleton
fun provideEmbeddingModel(): EmbeddingModel = FallbackHashEmbeddingModel()
```

El `FallbackHashEmbeddingModel` genera vectores deterministas de 384 dimensiones
basados en hash del texto. **NO usar en producción** — solo para desarrollo y tests
sin el archivo .tflite disponible.

## Especificaciones del modelo

| Parámetro | Valor |
|-----------|-------|
| Dimensiones de embedding | 384 |
| Tipo de cuantización | INT8 |
| Tamaño en disco | ~22 MB |
| Tokens máximos | 128 (AGENTS.md: chunk 500-1000 tokens por palabra) |
| Métricas de similaridad | Coseno (configurado en ObjectBox @HnswIndex) |

## Archivos de assets esperados

```
app/src/main/assets/
├── minilm_l6_v2_quantized.tflite   ← REQUERIDO para producción
└── (vacío en repo — ver ASSET_README.md)
```
