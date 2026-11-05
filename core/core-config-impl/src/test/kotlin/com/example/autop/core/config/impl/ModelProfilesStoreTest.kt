package com.example.autop.core.config.impl

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.example.autop.llm.ModelProfile
import com.example.autop.llm.ModelRoles
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [DataStoreModelProfileStore] 集成测试：真实 DataStore 文件、损坏与超前版本回退（docs/tech/03 §3）。 */
internal class ModelProfilesStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun upsertAndGetReturnsProfile() {
        runBlocking {
            val store = createStore()
            store.upsert(profile(PROFILE_A))
            assertThat(store.get(PROFILE_A)).isEqualTo(profile(PROFILE_A))
            assertThat(store.profiles.first()).containsExactly(profile(PROFILE_A))
        }
    }

    @Test
    fun upsertReplacesExistingInPlace() {
        runBlocking {
            val store = createStore()
            store.upsert(profile(PROFILE_A))
            store.upsert(profile(PROFILE_B))
            store.upsert(profile(PROFILE_A, displayName = "updated"))
            val ids = store.profiles.first().map { it.id }
            assertThat(ids).containsExactly(PROFILE_A, PROFILE_B).inOrder()
            assertThat(store.get(PROFILE_A)?.displayName).isEqualTo("updated")
        }
    }

    @Test
    fun deleteRemovesProfileAndClearsRoleReferences() {
        runBlocking {
            val store = createStore()
            store.upsert(profile(PROFILE_A))
            store.upsert(profile(PROFILE_B))
            store.setRoles(ModelRoles(main = PROFILE_A, vision = PROFILE_A, light = PROFILE_B))
            store.delete(PROFILE_A)
            assertThat(store.get(PROFILE_A)).isNull()
            assertThat(store.roles.first()).isEqualTo(ModelRoles(main = null, vision = null, light = PROFILE_B))
        }
    }

    @Test
    fun setRolesPersistsRoles() {
        runBlocking {
            val store = createStore()
            store.setRoles(ModelRoles(main = PROFILE_A))
            assertThat(store.roles.first()).isEqualTo(ModelRoles(main = PROFILE_A))
        }
    }

    @Test
    fun corruptJsonFallsBackToEmptyDocument() {
        runBlocking {
            file().writeText("{ not valid json")
            val store = createStore()
            assertThat(store.profiles.first()).isEmpty()
            assertThat(store.roles.first()).isEqualTo(ModelRoles())
        }
    }

    @Test
    fun newerSchemaVersionFallsBackToEmptyDocument() {
        runBlocking {
            file().writeText("""{"schemaVersion": ${MODEL_PROFILES_SCHEMA_VERSION + 1}, "profiles": [], "roles": {}}""")
            val store = createStore()
            assertThat(store.profiles.first()).isEmpty()
        }
    }

    private fun createStore(): DataStoreModelProfileStore = DataStoreModelProfileStore(createDataStore(file()))

    private fun file(): File = File(folder.root, "model_profiles.json")

    private fun createDataStore(target: File): DataStore<ModelProfilesDoc> =
        DataStoreFactory.create(
            serializer = ModelProfilesSerializer(),
            corruptionHandler = ReplaceFileCorruptionHandler { ModelProfilesDoc() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ) { target }

    private fun profile(
        id: String,
        displayName: String = "display-$id",
    ): ModelProfile =
        ModelProfile(
            id = id,
            displayName = displayName,
            protocol = "openai",
            baseUrl = "https://api.example.com",
            apiKeyRef = "secret:model:$id",
            model = "model-$id",
            contextWindow = 8192,
            maxOutputTokens = 256,
        )

    private companion object {
        const val PROFILE_A = "p-a"
        const val PROFILE_B = "p-b"
    }
}
