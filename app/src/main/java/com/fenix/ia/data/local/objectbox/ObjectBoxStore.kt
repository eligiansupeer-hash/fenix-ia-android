package com.fenix.ia.data.local.objectbox

import android.content.Context
import android.util.Log
import io.objectbox.BoxStore
import io.objectbox.exception.DbSchemaException
import java.io.File

/**
 * Singleton de BoxStore para ObjectBox.
 * Se inicializa una única vez en el módulo Hilt.
 */
object ObjectBoxStore {
    private const val TAG = "ObjectBoxStore"
    private const val RAG_STORE_DIR = "objectbox-rag"

    lateinit var store: BoxStore
        private set

    fun init(context: Context) {
        if (!::store.isInitialized) {
            val appContext = context.applicationContext
            val storeDir = File(appContext.filesDir, RAG_STORE_DIR)
            store = try {
                MyObjectBox.builder()
                    .androidContext(appContext)
                    .directory(storeDir)
                    .build()
            } catch (e: DbSchemaException) {
                Log.e(TAG, "ObjectBox schema error. RAG index was not deleted automatically.", e)
                throw e
            }
        }
    }
}
