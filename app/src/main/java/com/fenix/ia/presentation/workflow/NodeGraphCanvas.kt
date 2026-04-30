package com.fenix.ia.presentation.workflow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fenix.ia.domain.model.WorkflowStep

/**
 * Canvas nativo de Compose que dibuja el grafo de nodos del workflow.
 * Sin bibliotecas de terceros — solo Canvas API de Compose.
 *
 * Colores:
 *   Verde  (#4CAF50) → paso completado (index < currentStep)
 *   Azul   (#2196F3) → paso activo     (index == currentStep)
 *   Gris   (#9E9E9E) → paso pendiente  (index > currentStep)
 */
@Composable
fun NodeGraphCanvas(
    steps: List<WorkflowStep>,
    currentStep: Int,
    modifier: Modifier = Modifier
) {
    val colorDone    = Color(0xFF4CAF50)
    val colorActive  = Color(0xFF2196F3)
    val colorPending = Color(0xFF9E9E9E)
    val colorLine    = Color(0xFFBDBDBD)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        if (steps.isEmpty()) return@Canvas

        val nodeCount = steps.size
        val nodeW     = size.width / nodeCount
        val nodeH     = 56.dp.toPx()
        val topY      = (size.height - nodeH) / 2f
        val radius    = 10.dp.toPx()
        val lineY     = size.height / 2f

        steps.forEachIndexed { i, _ ->
            val leftX = i * nodeW + 6.dp.toPx()
            val rightX = leftX + nodeW - 12.dp.toPx()
            val centerX = leftX + (rightX - leftX) / 2f

            // Línea de conexión al siguiente nodo (dibujada antes del nodo para quedar debajo)
            if (i < steps.size - 1) {
                drawLine(
                    color       = colorLine,
                    start       = Offset(rightX, lineY),
                    end         = Offset(rightX + 12.dp.toPx(), lineY),
                    strokeWidth = 2.dp.toPx()
                )
            }

            val nodeColor = when {
                i < currentStep  -> colorDone
                i == currentStep -> colorActive
                else             -> colorPending
            }

            drawRoundRect(
                color       = nodeColor,
                topLeft     = Offset(leftX, topY),
                size        = Size(rightX - leftX, nodeH),
                cornerRadius = CornerRadius(radius, radius)
            )

            // Indicador de "activo" — anillo exterior pulsante (círculo más oscuro)
            if (i == currentStep) {
                drawRoundRect(
                    color        = colorActive.copy(alpha = 0.3f),
                    topLeft      = Offset(leftX - 3.dp.toPx(), topY - 3.dp.toPx()),
                    size         = Size(rightX - leftX + 6.dp.toPx(), nodeH + 6.dp.toPx()),
                    cornerRadius = CornerRadius(radius + 3.dp.toPx(), radius + 3.dp.toPx())
                )
            }
        }
    }
}
