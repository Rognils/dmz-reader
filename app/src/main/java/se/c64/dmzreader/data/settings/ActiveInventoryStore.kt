package se.c64.dmzreader.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Vilket inventory som ligger i Issue-tabellen just nu. */
data class ActiveInventory(val datasetVersion: String, val schemaVersion: Int)

interface ActiveInventoryStore {
    fun observe(): Flow<ActiveInventory?>
    suspend fun get(): ActiveInventory?
    suspend fun set(value: ActiveInventory)
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DataStoreActiveInventoryStore(context: Context) : ActiveInventoryStore {
    private val dataStore = context.applicationContext.settingsDataStore

    override fun observe(): Flow<ActiveInventory?> = dataStore.data.map { preferences ->
        val datasetVersion = preferences[DATASET_VERSION]
        val schemaVersion = preferences[SCHEMA_VERSION]
        if (datasetVersion != null && schemaVersion != null) {
            ActiveInventory(datasetVersion, schemaVersion)
        } else {
            null
        }
    }

    override suspend fun get(): ActiveInventory? = observe().first()

    override suspend fun set(value: ActiveInventory) {
        dataStore.edit { preferences ->
            preferences[DATASET_VERSION] = value.datasetVersion
            preferences[SCHEMA_VERSION] = value.schemaVersion
        }
    }

    private companion object {
        val DATASET_VERSION = stringPreferencesKey("active_dataset_version")
        val SCHEMA_VERSION = intPreferencesKey("active_schema_version")
    }
}
