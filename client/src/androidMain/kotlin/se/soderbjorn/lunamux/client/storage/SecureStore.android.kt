/**
 * Android `actual` for [SecureStore], backed by a durable private
 * `SharedPreferences` file with a best-effort one-time migration from the
 * legacy `EncryptedSharedPreferences` store.
 *
 * ## Why this is no longer EncryptedSharedPreferences
 *
 * The device-auth token used to live in `EncryptedSharedPreferences` (AES-256,
 * master key in the hardware-backed Android Keystore). That store is durable
 * *only while the Keystore master key stays valid*. A rotated or invalidated
 * key — lock-screen credential changes on some OEMs, keyset corruption, certain
 * restore/reinstall paths — makes every previously-written value undecryptable,
 * and `EncryptedSharedPreferences.create()` / `getString` then throw. The old
 * actual wrapped those calls in `runCatching { … }.getOrNull()`, silently
 * turning the throw into `null`; [LocalRepository.getOrCreateAuthToken] then saw
 * "no token yet" and minted a *fresh* one on every launch. The server saw each
 * as a brand-new device, so the user had to approve the connection again after
 * every app restart — and the approvals piled up as duplicate trusted devices.
 *
 * Surviving Keystore invalidation is impossible without a recoverable (i.e.
 * non-Keystore-encrypted) copy of the value, and any recoverable copy is
 * plaintext on disk anyway — so encryption-at-rest bought nothing here but the
 * outage. The token is a low-sensitivity LAN bearer credential already
 * protected by TLS certificate pinning, an explicit server-side approval gate,
 * and the default-off allow-remote switch. A private `SharedPreferences` file
 * (removed on uninstall like all app-private storage, but immune to Keystore
 * churn) is the right durability/secrecy trade-off for it.
 *
 * Devices whose encrypted token is still readable are migrated transparently on
 * the first [read], so no re-approval is forced on upgrade. A device whose
 * encrypted store was *already* broken can't be migrated — but that device was
 * being re-prompted on every launch regardless, and now stops.
 *
 * The application [android.content.Context] is read from [LocalStoreContext]
 * (set once in `MainActivity.onCreate`, before any store is constructed), the
 * same holder the Android [LocalStore] uses. All operations run on
 * [Dispatchers.IO], and every failure is logged rather than swallowed.
 *
 * @see LocalRepository.getOrCreateAuthToken
 */
package se.soderbjorn.lunamux.client.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android `actual` of [SecureStore]. Durable private `SharedPreferences`, with a
 * best-effort migration from the legacy encrypted store on first read.
 */
actual class SecureStore {

    /**
     * The durable store and source of truth: a private `SharedPreferences` file.
     * Survives everything short of an uninstall or an explicit "clear data", and
     * — unlike the encrypted store it replaces — is immune to the Android
     * Keystore churn that was losing the token.
     */
    private val prefs: SharedPreferences by lazy {
        LocalStoreContext.appContext.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
    }

    /**
     * The legacy `EncryptedSharedPreferences`, or `null` when it can't be built
     * on this device (exactly the failure that motivated moving off it). Opened
     * lazily and read only to migrate a pre-existing token into [prefs]; never
     * written to for new values.
     */
    private val legacyEncrypted: SharedPreferences? by lazy { openLegacyEncrypted() }

    /**
     * Best-effort open of the legacy encrypted preferences file. Any failure to
     * build it (an invalidated master key, a corrupt keyset) means there is no
     * migratable token there, which is fine — return `null` and log.
     *
     * @return the legacy encrypted store, or `null` if it can't be opened.
     */
    private fun openLegacyEncrypted(): SharedPreferences? = runCatching {
        val context = LocalStoreContext.appContext
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            LEGACY_ENCRYPTED_PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure {
        Log.w(TAG, "Legacy EncryptedSharedPreferences unavailable; skipping migration", it)
    }.getOrNull()

    /**
     * Read [key] from the durable store; on a miss, migrate a value written by
     * an older build from the legacy encrypted store (once), then return it.
     *
     * @param key the entry key (e.g. [SECURE_AUTH_TOKEN_KEY]).
     * @return the stored value, or `null` if absent in both stores.
     */
    actual suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        runCatching { prefs.getString(key, null) }
            .onFailure { Log.w(TAG, "read('$key') from durable store failed", it) }
            .getOrNull()
            ?.let { return@withContext it }
        // Durable store has nothing yet — try a one-time migration of a token
        // written by an older build into the encrypted store.
        val legacy = legacyEncrypted?.let { enc ->
            runCatching { enc.getString(key, null) }
                .onFailure { Log.w(TAG, "legacy encrypted read('$key') failed", it) }
                .getOrNull()
        } ?: return@withContext null
        runCatching { prefs.edit().putString(key, legacy).commit() }
            .onFailure { Log.w(TAG, "migrating '$key' into durable store failed", it) }
        Log.i(TAG, "migrated '$key' from the legacy encrypted store into the durable store")
        legacy
    }

    /**
     * Persist [value] under [key] in the durable store. Unlike the old actual, a
     * failure to persist is logged (and surfaced via the `commit()` result)
     * rather than silently dropped.
     *
     * @param key the entry key (e.g. [SECURE_AUTH_TOKEN_KEY]).
     * @param value the value to store.
     */
    actual suspend fun write(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        val persisted = runCatching { prefs.edit().putString(key, value).commit() }
            .onFailure { Log.e(TAG, "write('$key') threw", it) }
            .getOrDefault(false)
        if (!persisted) Log.e(TAG, "write('$key') did not persist")
        Unit
    }

    /**
     * Remove [key] from the durable store, and from the legacy encrypted store
     * too so a stale copy can't be migrated back in later.
     *
     * @param key the entry key to remove.
     */
    actual suspend fun delete(key: String): Unit = withContext(Dispatchers.IO) {
        runCatching { prefs.edit().remove(key).commit() }
            .onFailure { Log.w(TAG, "delete('$key') from durable store failed", it) }
        legacyEncrypted?.let { enc ->
            runCatching { enc.edit().remove(key).commit() }
                .onFailure { Log.w(TAG, "delete('$key') from legacy encrypted store failed", it) }
        }
        Unit
    }

    private companion object {
        /** Logcat tag for this store's diagnostics. */
        const val TAG = "SecureStore"

        /** Durable private preferences file backing the store. */
        const val PREFS_FILE_NAME = "termtastic_device"

        /**
         * The old `EncryptedSharedPreferences` file name, migrated from on first
         * read. Kept verbatim so an existing device's token carries over.
         */
        const val LEGACY_ENCRYPTED_PREFS_FILE_NAME = "termtastic_secure"
    }
}
