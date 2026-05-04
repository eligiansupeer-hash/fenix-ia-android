package com.fenix.ia.audit

import com.fenix.ia.BuildConfig

enum class AuditMode {
    FULL_TEST,
    SAFE_PRODUCTION,
    OFF;

    companion object {
        val current: AuditMode by lazy {
            entries.firstOrNull { it.name == BuildConfig.AUDIT_MODE } ?: SAFE_PRODUCTION
        }
    }
}
