/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.workflow.trigger

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowDefinition

/**
 * Phase 12 — `app_launched` / `app_closed` triggers, fed by the existing accessibility
 * service's foreground-app event stream.
 *
 * The accessibility service calls [AppForegroundDispatcher.onForegroundChange] on every
 * `TYPE_WINDOW_STATE_CHANGED` event with the new package name. This family de-dupes
 * (only fire on actual transition between packages), then matches against the matching
 * workflows.
 *
 * Battery hygiene: the accessibility service itself stays running for screen-automation
 * tools, so this family adds no resource cost when no workflows use it. The family just
 * stops dispatching when [matching] is empty.
 *
 * `app_foreground_duration`: when an app enters the foreground, a delayed coroutine is
 * scheduled per matching workflow. If the app is *still* in the foreground after the
 * delay, the workflow fires. If the user switched away before the delay elapsed, the
 * coroutine is cancelled and the workflow does not fire — so it only triggers on
 * *continuous* usage, not just a brief foreground visit.
 */
internal class AppForegroundTriggerFamily(
    private val scope: CoroutineScope,
) : WorkflowTriggerFamily {

    override val name = "app_foreground"

    @Volatile private var matching: List<WorkflowDefinition> = emptyList()
    @Volatile private var fireCallback: TriggerFireCallback? = null
    @Volatile private var lastForegroundPackage: String? = null

    /** Active duration-timer jobs keyed by workflow id. Cancelled on app switch-out. */
    private val durationJobs = mutableMapOf<String, Job>()
    private val durationJobsMutex = Mutex()

    override fun handles(spec: TriggerSpec): Boolean =
        spec is TriggerSpec.AppLaunched ||
            spec is TriggerSpec.AppClosed ||
            spec is TriggerSpec.AppForegroundDuration

    override suspend fun sync(matching: List<WorkflowDefinition>, callback: TriggerFireCallback) {
        this.matching = matching
        this.fireCallback = callback
        AppForegroundDispatcher.bind(this)
        // Cancel any duration timers for workflows that no longer exist / are disabled.
        val liveIds = matching.map { it.id }.toSet()
        durationJobsMutex.withLock {
            durationJobs.keys.toList().filter { it !in liveIds }.forEach { id ->
                durationJobs.remove(id)?.cancel()
            }
        }
    }

    override suspend fun shutdown() {
        matching = emptyList()
        fireCallback = null
        lastForegroundPackage = null
        durationJobsMutex.withLock {
            durationJobs.values.forEach { it.cancel() }
            durationJobs.clear()
        }
    }

    /** Called by [AppForegroundDispatcher.onForegroundChange] from accessibility service. */
    fun onForegroundChange(newPackage: String?) {
        if (newPackage.isNullOrBlank()) return
        val prev = lastForegroundPackage
        lastForegroundPackage = newPackage
        if (prev == newPackage) return  // no transition
        val cb = fireCallback ?: return
        val snap = matching
        if (snap.isEmpty()) return

        // --- app_launched / app_closed: fire immediately on transition ---
        val fires = mutableListOf<Pair<String, TriggerSpec>>()
        for (wf in snap) {
            when (val t = wf.trigger) {
                is TriggerSpec.AppLaunched ->
                    if (t.packageName == newPackage) fires += wf.id to t
                is TriggerSpec.AppClosed ->
                    if (prev != null && t.packageName == prev) fires += wf.id to t
                else -> Unit
            }
        }
        if (fires.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                for ((wfId, spec) in fires) {
                    runCatching { cb.onFire(wfId, spec) }.onFailure {
                        Log.w(TAG, "app_foreground: fire failed for wf=$wfId", it)
                    }
                }
            }
        }

        // --- app_foreground_duration: schedule delayed timers for the newly-foreground app ---
        scope.launch(Dispatchers.IO) { scheduleDurationTimers(newPackage) }
    }

    /**
     * For every `app_foreground_duration` workflow targeting [packageName], start a delayed
     * coroutine. When the delay elapses, re-check that [AppForegroundLastKnown] still points
     * at [packageName] (user hasn't switched away) before firing.
     */
    private suspend fun scheduleDurationTimers(packageName: String) {
        val cb = fireCallback ?: return
        val snap = matching
        val durationSpecs = snap.mapNotNull { wf ->
            val t = wf.trigger as? TriggerSpec.AppForegroundDuration ?: return@mapNotNull null
            if (t.packageName == packageName) wf.id to t else null
        }
        if (durationSpecs.isEmpty()) return

        // Cancel stale timers from the previous foreground app before starting new ones.
        // (durationJobs only holds timers for the *current* foreground app; switching away
        // cancels them in the branch below, but we double-check here for safety.)
        durationJobsMutex.withLock {
            durationJobs.values.forEach { it.cancel() }
            durationJobs.clear()
        }

        for ((wfId, spec) in durationSpecs) {
            val job = scope.launch(Dispatchers.IO) {
                delay(spec.minutes.toLong() * 60_000L)
                // Re-check: is the app still in the foreground?
                if (AppForegroundLastKnown.value == spec.packageName) {
                    runCatching { cb.onFire(wfId, spec) }.onFailure {
                        Log.w(TAG, "app_foreground_duration: fire failed for wf=$wfId", it)
                    }
                }
                // Timer consumed — clean up its own entry.
                durationJobsMutex.withLock { durationJobs.remove(wfId) }
            }
            durationJobsMutex.withLock { durationJobs[wfId] = job }
        }
    }

    companion object { private const val TAG = "WorkflowTrigger" }
}

/** Bridge from [me.rerere.rikkahub.service.RikkaAccessibilityService] to the family. */
object AppForegroundDispatcher {
    @Volatile private var family: AppForegroundTriggerFamily? = null
    @Volatile private var foregroundConsumerCount: Int = 0
    internal fun bind(f: AppForegroundTriggerFamily) { family = f }

    private val extraListeners = java.util.concurrent.CopyOnWriteArrayList<(String?) -> Unit>()
    fun addListener(listener: (String?) -> Unit) { extraListeners.add(listener) }
    fun removeListener(listener: (String?) -> Unit) { extraListeners.remove(listener) }

    /**
     * Track how many workflows currently NEED the foreground-package cache. The trigger
     * registry calls this on every resync - if zero workflows have an `app_launched`/`_closed`/
     * `_foreground_duration` trigger AND no condition uses `foreground_app_*`, the dispatcher's
     * hot path can no-op entirely. Without this gate the accessibility service writes the
     * volatile cache on every TYPE_WINDOW_STATE_CHANGED event (which fires multiple times per
     * app switch + every dialog/menu pop), paying 24/7 for a feature most users won't enable.
     */
    internal fun setForegroundConsumerCount(n: Int) { foregroundConsumerCount = n }

    /** Called from accessibility service's TYPE_WINDOW_STATE_CHANGED handler. */
    fun onForegroundChange(packageName: String?) {
        if (family == null && foregroundConsumerCount == 0 && extraListeners.isEmpty()) return
        val prev = AppForegroundLastKnown.value
        if (prev == packageName) return
        AppForegroundLastKnown.value = packageName
        family?.onForegroundChange(packageName)
        extraListeners.forEach { it(packageName) }
    }
}

/** Last-known foreground package, exposed for ContextProvider's foreground_app_* conditions. */
object AppForegroundLastKnown {
    @Volatile var value: String? = null
}
