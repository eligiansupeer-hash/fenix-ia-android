package com.fenix.ia.data.local.objectbox

import android.content.Context
import io.objectbox.BoxStore

/**
 * Singleton de BoxStore para ObjectBox.
 * Se inicializa una única vez en el módulo Hilt.
 */
object ObjectBoxStore {
    lateinit var store: BoxStore
        private set

    fun init(context: Context) {
        if (!::store.isInitialized) {
            store = MyObjectBox.builder()
                .androidContext(context.applicationContext)
                .build()
        }
    }
}
