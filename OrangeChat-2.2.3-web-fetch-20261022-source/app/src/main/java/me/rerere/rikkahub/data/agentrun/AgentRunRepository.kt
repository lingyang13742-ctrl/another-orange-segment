/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.agentrun

import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * No-op stub of the agent fork's unified AgentRun ledger.
 *
 * The upstream ExTV/rikkahub-agent persists one cross-pillar "agent_runs" row per autonomous
 * run (cron / workflow / sub-agent / telegram / external-automation) for observability and
 * boot recovery. orangechat does not port that full ledger surface, so this stub keeps the
 * [me.rerere.rikkahub.workflow.execution.WorkflowEngine] source identical to upstream while
 * making the ledger calls cheap no-ops. [open] still returns a stable id so callers can keep
 * a handle; [markTerminal] simply discards it. If the ledger is ever ported, swap this class
 * for the Room-backed implementation without touching the engine.
 */
class AgentRunRepository {

    suspend fun open(
        kind: AgentRunKind,
        domainId: String,
        parentRunId: String? = null,
        metadata: JsonObject? = null,
        status: AgentRunStatus = AgentRunStatus.running,
    ): String = Uuid.random().toString()

    suspend fun markTerminal(
        id: String,
        status: AgentRunStatus,
        lastError: String? = null,
    ) = Unit
}

@Suppress("EnumEntryName")
enum class AgentRunStatus {
    queued,
    awaiting_approval,
    running,
    succeeded,
    failed,
    cancelled,
    process_lost;

    val isTerminal: Boolean
        get() = this == succeeded || this == failed || this == cancelled || this == process_lost
}

enum class AgentRunKind(val wire: String) {
    Cron("cron"),
    Workflow("workflow"),
    SubAgent("subagent"),
    Telegram("telegram"),
    ExternalAutomation("external_automation");
}
