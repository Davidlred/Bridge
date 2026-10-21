package app.thebridge.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Light, dark or the system setting (the default). Kept on the device; it survives logging out. */
@Singleton
class Appearance @Inject constructor(private val store: DataStore<Preferences>) {
    val mode: Flow<String> = store.data.map { it[KEY]?.takeIf { m -> m in MODES } ?: "system" }
    suspend fun set(mode: String) { if (mode in MODES) store.edit { it[KEY] = mode } }

    companion object {
        val MODES = listOf("system", "light", "dark")
        private val KEY = stringPreferencesKey("appearance")
    }
}
