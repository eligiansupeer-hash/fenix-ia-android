package com.fenix.ia.audit

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput

fun Modifier.auditTouches(screenName: () -> String?): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes
                .firstOrNull { it.changedToDownIgnoreConsumed() }
                ?.let { AuditLogger.tap(screenName(), it.position.x, it.position.y) }
        }
    }
}
