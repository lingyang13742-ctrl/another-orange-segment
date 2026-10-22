package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.search.SearchServiceOptions
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** Opt-in real model smoke test. API key stays in the localhost relay, never in the APK or settings. */
@RunWith(AndroidJUnit4::class)
class DeepSeekWebFetchInstrumentedTest {
    @Test
    fun realModelChoosesFetchForUrls(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live_network") == "true")
        val koin = GlobalContext.get()
        val store = koin.get<SettingsStore>()
        val original = store.settingsFlowRaw.first()
        val model = Model(modelId = "deepseek-flash", displayName = "DeepSeek Flash（真实模型）", abilities = listOf(ModelAbility.TOOL))
        val assistant = Assistant(
            name = "DeepSeek 网页抓取实测", chatModelId = model.id,
            localTools = listOf(LocalToolOption.WebFetch), streamOutput = false,
            maxTokens = 700, temperature = 0f,
        )
        val provider = ProviderSetting.OpenAI(
            name = "DeepSeek 实测转发", models = listOf(model), apiKey = "local-relay-no-secret",
            baseUrl = "http://127.0.0.1:18766/v1",
        )
        val base = original.copy(
            assistantId = assistant.id, assistants = original.assistants + assistant,
            providers = original.providers + provider, chatModelId = model.id,
            forceConfirmToolCalls = false, searchServices = listOf(SearchServiceOptions.BingLocalOptions()),
            searchServiceSelected = 0,
        )
        val chat = koin.get<ChatService>()
        val failures = mutableListOf<String>()
        val cases = listOf(
            Triple("搜索关闭·普通网址", false, "请用中文简要总结这个网页：https://example.com/"),
            Triple("搜索开启·GitHub", true, "请用中文简要总结这个网页：https://github.com/sue1231513/orangechat"),
            Triple("搜索开启·只发网址", true, "https://example.com/"),
            Triple("搜索开启·中文网页", true, "请用中文简要总结这个网页：https://www.runoob.com/html/html-tutorial.html"),
        )
        for ((name, searchEnabled, prompt) in cases) {
            store.update(base.copy(enableWebSearch = searchEnabled))
            val conversation = Conversation(assistantId = assistant.id, title = "DeepSeek 实测：$name", messageNodes = emptyList())
            koin.get<ConversationRepository>().insertConversation(conversation)
            chat.initializeConversation(conversation.id)
            Log.i("DeepSeekLive", "CASE_START name=$name search_enabled=$searchEnabled conversation=${conversation.id}")
            chat.sendMessage(conversation.id, listOf(UIMessagePart.Text(prompt)))
            try {
                val job = withTimeout(10_000) { chat.getGenerationJobStateFlow(conversation.id).first { it != null }!! }
                withTimeout(120_000) { job.join() }
                delay(500)
                val result = chat.getConversationFlow(conversation.id).value
                val parts = result.currentMessages.flatMap { it.parts }
                val calls = parts.filterIsInstance<UIMessagePart.Tool>()
                val names = calls.map { it.toolName }
                val errors = chat.errors.value.filter { it.conversationId == conversation.id }
                val fetchOutput = calls.firstOrNull { it.toolName == "web_fetch" }?.output?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }.orEmpty()
                val output = runCatching { Json.parseToJsonElement(fetchOutput).jsonObject }.getOrNull()
                val answer = (parts.lastOrNull() as? UIMessagePart.Text)?.text.orEmpty()
                val passed = names.firstOrNull() == "web_fetch" && "search_web" !in names &&
                    output?.get("success")?.jsonPrimitive?.booleanOrNull == true && answer.isNotBlank() && errors.isEmpty()
                Log.i("DeepSeekLive", "CASE_RESULT name=$name passed=$passed tools=$names fetch_success=${output?.get("success")} answer_chars=${answer.length} errors=${errors.map { it.error.message }}")
                Log.i("DeepSeekLive", "ANSWER $answer")
                if (!passed) failures.add("$name: tools=$names, errors=${errors.map { it.error.message }}")
            } catch (e: Exception) {
                chat.stopGeneration(conversation.id)
                failures.add("$name: ${e.message}")
                Log.e("DeepSeekLive", "CASE_ERROR $name: ${e.message}")
            }
        }
        store.update(base.copy(enableWebSearch = false))
        assertTrue("Real-model test failures: $failures", failures.isEmpty())
    }
}
