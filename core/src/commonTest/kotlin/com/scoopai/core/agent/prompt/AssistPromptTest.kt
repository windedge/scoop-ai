package com.scoopai.core.agent.prompt

import com.scoopai.core.AssistContext
import com.scoopai.core.agent.AnalyzeOutput
import com.scoopai.core.agent.AnalyzeStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Prompt tests for the finalize explanation protocol (architecture proposal 4.5)
 * as applied to the ASSIST phase (user follow-up, explanatory=true):
 * - assist prompt: conditional explanation guidance plus the Question Escape Hatch.
 */
class AssistPromptTest {

    private fun assistContext() = AssistContext(
        manifest = """{"version":"1.0.0"}""",
        feedback = "Remove bin, keep shortcuts",
        retryCount = 1,
        explanatory = true,
    )

    @Test
    fun `buildAssistPrompt uses the assistant identity and mentions the explanation parameter`() {
        val prompt = buildAssistPrompt(assistContext())

        assertTrue(
            prompt.contains("You are a Scoop manifest assistant."),
            "assist prompt must open with the assistant identity, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("explanation"),
            "explanatory assist prompt must mention the explanation parameter",
        )
    }

    @Test
    fun `buildAssistPrompt requires answering which fields were added removed or modified`() {
        val prompt = buildAssistPrompt(assistContext())

        assertTrue(
            prompt.contains("added") && prompt.contains("removed") && prompt.contains("modified"),
            "explanation guidance must require stating which fields were added/removed/modified, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt requires the explanation to follow the user language`() {
        val prompt = buildAssistPrompt(assistContext())

        assertTrue(
            prompt.contains("language"),
            "explanation guidance must require following the user's language",
        )
    }

    @Test
    fun `buildAssistPrompt caps the explanation at 3 short sentences`() {
        val prompt = buildAssistPrompt(assistContext())

        assertTrue(
            prompt.contains("3 short sentences"),
            "explanatory assist prompt must cap the explanation length, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("60 words"),
            "explanatory assist prompt must give a word budget, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt includes the question escape hatch and answered guidance`() {
        val prompt = buildAssistPrompt(assistContext())

        assertTrue(
            prompt.contains("Question Escape Hatch"),
            "assist prompt must include the Question Escape Hatch section, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("answered"),
            "assist prompt must mention the Boolean `answered` parameter, got:\n$prompt",
        )
    }

    // ---------------------------------------------------------------
    // buildAssistPrompt: install test honesty section (B2)
    // ---------------------------------------------------------------

    @Test
    fun `buildAssistPrompt without install test says no install test was performed`() {
        val prompt = buildAssistPrompt(assistContext(), installTestActive = false)

        assertTrue(
            prompt.contains("No install test was performed"),
            "assist prompt must state honestly that no install test ran, got:\n$prompt",
        )
        assertFalse(
            prompt.contains("automatically install-tested"),
            "inactive prompt must not claim an automatic install test",
        )
    }

    @Test
    fun `buildAssistPrompt with installTestActive gives verify_install usage guidance`() {
        val prompt = buildAssistPrompt(assistContext(), installTestActive = true)

        assertTrue(
            prompt.contains("verify_install"),
            "active prompt must expose the verify_install tool, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("real scoop install") || prompt.contains("REAL scoop install"),
            "active prompt must describe the real install test, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with installTestActive instructs to call only when the user asks`() {
        val prompt = buildAssistPrompt(assistContext(), installTestActive = true)

        assertTrue(
            prompt.contains("only when the user explicitly asks") || prompt.contains("ONLY when the user explicitly asks"),
            "active prompt must gate verify_install behind an explicit user request, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with installTestActive requires a separate turn before finalize`() {
        val prompt = buildAssistPrompt(assistContext(), installTestActive = true)

        assertTrue(
            prompt.contains("own separate turn") || prompt.contains("OWN separate turn"),
            "active prompt must require verify_install in its own turn, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("finalize_task_result"),
            "active prompt must reference the finalize tool for the separate-turn rule",
        )
    }

    @Test
    fun `buildAssistPrompt with installTestActive says verify re-runs the test after fix`() {
        val prompt = buildAssistPrompt(assistContext(), installTestActive = true)

        assertTrue(
            prompt.contains("re-runs the install test") || prompt.contains("re-runs the install test automatically"),
            "active prompt must say the verify phase re-runs the install test, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("run a second verify_install") || prompt.contains("do NOT run a second") || prompt.contains("Do NOT run a second"),
            "active prompt must forbid a second verify_install inside the assist subgraph, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with failure details in the message forbids re-running the install test`() {
        val prompt = buildAssistPrompt(assistContext(), installTestActive = true)

        assertTrue(
            prompt.contains("do NOT call verify_install") || prompt.contains("do NOT call `verify_install`"),
            "active prompt must forbid reproducing the failure via verify_install when details are already present, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("error summary or log excerpt") || prompt.contains("install test failure details"),
            "active prompt must reference the failure details the user's message already contains",
        )
    }

    @Test
    fun `buildAssistPrompt with installTestActive no longer claims automatic install test`() {
        val prompt = buildAssistPrompt(assistContext(), installTestActive = true)

        assertFalse(
            prompt.contains("automatically install-tested"),
            "B2 removed the automatic-install-test claim",
        )
    }

    @Test
    fun `buildRepairPrompt forbids verify_install and mentions the verify phase owns it`() {
        val repairContext = AssistContext(
            manifest = """{"version":"1.0.0"}""",
            feedback = "fix bin",
            retryCount = 1,
            explanatory = false,
        )

        val prompt = buildRepairPrompt(repairContext)
        assertTrue(
            prompt.contains("Do NOT call verify_install") || prompt.contains("Do NOT call `verify_install`"),
            "repair prompt must ban calling verify_install, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("verify phase"),
            "repair prompt must say the verify phase owns install testing, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt default keeps backward compatible no-install-test wording`() {
        val prompt = buildAssistPrompt(assistContext())
        assertTrue(
            prompt.contains("No install test was performed"),
            "the one-argument overload must behave as installTestActive=false",
        )
    }

    // ---------------------------------------------------------------
    // buildAssistPrompt: probe 失败根因自诊断 + 显式请求示例（装测不触发）
    // ---------------------------------------------------------------

    @Test
    fun `buildAssistPrompt without install test states the unavailability reason when provided`() {
        val prompt = buildAssistPrompt(
            assistContext(),
            installTestActive = false,
            assistTestUnavailableReason = "'scoop --version' exited with 9009",
        )

        assertTrue(
            prompt.contains("Install testing is unavailable"),
            "OFF prompt with a reason must tell the model install testing is unavailable, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("exited with 9009"),
            "OFF prompt must include the concrete probe failure reason, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("scoop is on PATH"),
            "unavailability line must tell the model to suggest checking PATH, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt without a reason keeps the plain no-install-test wording`() {
        val prompt = buildAssistPrompt(assistContext(), installTestActive = false)

        assertFalse(
            prompt.contains("Install testing is unavailable"),
            "no reason provided must not add an unavailability line, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with installTestActive lists explicit install-test request phrasings`() {
        val prompt = buildAssistPrompt(assistContext(), installTestActive = true)

        assertTrue(
            prompt.contains("Explicit install-test requests include phrasings like"),
            "active prompt must give explicit request examples for flash models, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("test install it"),
            "example phrasings must include the exact user wording, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("run the install test"),
            "example phrasings must include the run-the-test wording, got:\n$prompt",
        )
    }

    // ---------------------------------------------------------------
    // buildAssistPrompt / buildRepairPrompt: save_to_bucket guidance (P2)
    // ---------------------------------------------------------------

    private fun repairContext() = AssistContext(
        manifest = """{"version":"1.0.0"}""",
        feedback = "fix bin",
        retryCount = 1,
        explanatory = false,
    )

    @Test
    fun `buildAssistPrompt with saveActive gives save_to_bucket usage guidance`() {
        val prompt = buildAssistPrompt(assistContext(), saveActive = true)

        assertTrue(
            prompt.contains("save_to_bucket"),
            "active prompt must expose the save_to_bucket tool, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("explicitly"),
            "active prompt must gate save_to_bucket behind an explicit user request, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("overwrite=true"),
            "active prompt must describe the overwrite retry, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("needsOverwrite"),
            "active prompt must explain the already-exists signal, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with saveActive says bucket and name default to host rules when omitted`() {
        val prompt = buildAssistPrompt(assistContext(), saveActive = true)

        // A save without an explicit bucket no longer falls back to host defaults:
        // the host returns needsBucket and the model asks via __ask_user__.
        assertTrue(
            prompt.contains("needsBucket"),
            "save guidance must describe the needsBucket reply, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("__ask_user__"),
            "save guidance must route the missing bucket through ask_user, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt without saveActive does not mention save_to_bucket`() {
        val prompt = buildAssistPrompt(assistContext(), installTestActive = true)

        assertFalse(
            prompt.contains("save_to_bucket"),
            "no-handler prompt must not advertise save_to_bucket, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with saveActive instructs to ask the user first when bucket is unspecified`() {
        val prompt = buildAssistPrompt(assistContext(), saveActive = true)

        assertTrue(
            prompt.contains("did not specify a bucket"),
            "save guidance must handle the no-bucket case by asking first, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("answered=true"),
            "the ask-first flow must go through the answered=true escape hatch, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("same bucket again"),
            "a previously confirmed bucket must be reused without asking again, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with saveActive skips re-verification after an unchanged save`() {
        val prompt = buildAssistPrompt(assistContext(), saveActive = true)

        assertTrue(
            prompt.contains("save succeeded and the manifest did not change"),
            "save guidance must define the unchanged-save outcome, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("manifest verbatim"),
            "unchanged save must finalize with the manifest verbatim, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("no further verification"),
            "unchanged save must be final, no re-verification, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with saveActive marks save plus further changes as needing another round`() {
        val prompt = buildAssistPrompt(assistContext(), saveActive = true)

        assertTrue(
            prompt.contains("both saved AND further changed"),
            "combined save+changes must be called out explicitly, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("answered=false"),
            "combined save+changes must not use the answered=true shortcut, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with saveActive requires ask_user for a needsBucket result`() {
        val prompt = buildAssistPrompt(assistContext(), saveActive = true)

        assertTrue(
            prompt.contains("needsBucket:true with a buckets array"),
            "save guidance must tell the model to react to the needsBucket result, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("__ask_user__ with ONE single-choice question"),
            "save guidance must require exactly one single-choice ask_user question, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("listing exactly those buckets as options"),
            "ask_user options must be exactly the buckets array, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with saveActive forbids retrying save without a bucket after needsBucket`() {
        val prompt = buildAssistPrompt(assistContext(), saveActive = true)

        assertTrue(
            prompt.contains("Never complete a save without an explicit bucket"),
            "save guidance must forbid completing a save without an explicit bucket, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("do not retry save_to_bucket without a bucket"),
            "save guidance must forbid a bucket-less retry after needsBucket, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with saveActive does not tell the model to rely on host bucket defaults`() {
        val prompt = buildAssistPrompt(assistContext(), saveActive = true)

        assertFalse(
            prompt.contains("optional: omit them to let the host apply its defaults"),
            "bucket/name must no longer be advertised as optional with host defaults, got:\n$prompt",
        )
        assertFalse(
            prompt.contains("last used, then main, then the first bucket"),
            "the host default bucket chain must be removed from the prompt, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with saveActive treats runtime failure reports as a fix-save-reinstall request`() {
        val prompt = buildAssistPrompt(assistContext(), saveActive = true)

        assertTrue(
            prompt.contains("treat it as a manifest fix request"),
            "save guidance must treat runtime failure reports as manifest fix requests, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("fix-save-reinstall loop"),
            "save guidance must bound the fix-save-reinstall loop, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("SAME bucket and overwrite=true"),
            "the fix-save-reinstall flow must reuse the same bucket with overwrite, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("then reinstall the app from the bucket"),
            "the fix-save-reinstall flow must end in a reinstall, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("the overwrite gate comes first"),
            "the fix-save-reinstall flow must run the needsOverwrite confirmation before any overwrite, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("never pass `overwrite=true` on the first re-save to bypass the confirmation"),
            "the first re-save must not bypass the overwrite confirmation, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("already installed"),
            "save guidance must handle already-installed reports without blind retries, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with saveActive and installActive wires the reinstall to install_app`() {
        val prompt = buildAssistPrompt(assistContext(), saveActive = true, installActive = true)

        assertTrue(
            prompt.contains("For the reinstall step above, call `install_app`"),
            "with an install handler the reinstall step must name install_app, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("same bucket and name the manifest was saved under"),
            "the reinstall must reuse the save bucket and name, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("If install_app reports the app as already installed, do not retry"),
            "install_app must not be retried when the app is already installed, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("ask the user to uninstall it first"),
            "the already-installed guidance must tell the user to uninstall first, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with saveActive lists common runtime failure root causes`() {
        val prompt = buildAssistPrompt(assistContext(), saveActive = true)

        assertTrue(
            prompt.contains("wrong bin paths"),
            "save guidance must list wrong bin paths as a common runtime failure cause, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("missing extract_dir"),
            "save guidance must list missing extract_dir as a common runtime failure cause, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("incorrect shortcut targets"),
            "save guidance must list incorrect shortcut targets as a common runtime failure cause, got:\n$prompt",
        )
    }

    @Test
    fun `buildRepairPrompt with saveActive keeps the ban and omits the interactive save guidance`() {
        val prompt = buildRepairPrompt(repairContext(), saveActive = true)

        assertTrue(
            prompt.contains("Do NOT call `save_to_bucket`"),
            "repair prompt must keep the save_to_bucket ban, got:\n$prompt",
        )
        assertFalse(
            prompt.contains("did not specify a bucket"),
            "repair prompt must not contain interactive save guidance, got:\n$prompt",
        )
    }

    @Test
    fun `buildRepairPrompt with saveActive forbids save_to_bucket`() {
        val prompt = buildRepairPrompt(repairContext(), saveActive = true)

        assertTrue(
            prompt.contains("save_to_bucket"),
            "repair prompt with a handler must explicitly ban save_to_bucket, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("Do NOT call `save_to_bucket`"),
            "repair prompt must forbid saving mid-repair, got:\n$prompt",
        )
    }

    @Test
    fun `buildRepairPrompt without saveActive does not mention save_to_bucket`() {
        val prompt = buildRepairPrompt(repairContext())

        assertFalse(
            prompt.contains("save_to_bucket"),
            "no-handler repair prompt must not mention save_to_bucket, got:\n$prompt",
        )
    }

    // ---------------------------------------------------------------
    // buildAssistPrompt / buildRepairPrompt: install_app guidance (P3)
    // ---------------------------------------------------------------

    @Test
    fun `buildAssistPrompt with installActive gives install_app usage guidance`() {
        val prompt = buildAssistPrompt(assistContext(), saveActive = true, installActive = true)

        assertTrue(
            prompt.contains("install_app"),
            "active prompt must expose the install_app tool, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("explicitly"),
            "active prompt must gate install_app behind an explicit user request, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("save_to_bucket"),
            "install guidance must require the manifest to be saved first, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("already installed"),
            "active prompt must explain the already-installed report, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt with installActive offers the direct no-bucket install path`() {
        val prompt = buildAssistPrompt(assistContext(), installActive = true)

        assertTrue(
            prompt.contains("omit `bucket`)"),
            "install guidance must offer the direct no-bucket install path, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("save_to_bucket"),
            "install guidance must still recommend saving to a bucket first, got:\n$prompt",
        )
    }

    @Test
    fun `buildAssistPrompt without installActive does not mention install_app`() {
        val prompt = buildAssistPrompt(assistContext(), installTestActive = true, saveActive = true)

        assertFalse(
            prompt.contains("install_app"),
            "no-handler prompt must not advertise install_app, got:\n$prompt",
        )
    }

    @Test
    fun `buildRepairPrompt with installActive forbids install_app`() {
        val prompt = buildRepairPrompt(repairContext(), installActive = true)

        assertTrue(
            prompt.contains("install_app"),
            "repair prompt with a handler must explicitly ban install_app, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("Do NOT call `install_app`"),
            "repair prompt must forbid installing mid-repair, got:\n$prompt",
        )
    }

    @Test
    fun `buildRepairPrompt without installActive does not mention install_app`() {
        val prompt = buildRepairPrompt(repairContext())

        assertFalse(
            prompt.contains("install_app"),
            "no-handler repair prompt must not mention install_app, got:\n$prompt",
        )
    }

    // ---------------------------------------------------------------
    // buildGeneratePrompt: optional explanation parameter note
    // ---------------------------------------------------------------

    private fun minimalAnalyze() = AnalyzeOutput(
        status = AnalyzeStatus.OK,
        strategy = "portable_zip",
        entries = emptyList(),
        bin = emptyList(),
    )

    @Test
    fun `buildGeneratePrompt mentions the optional explanation parameter`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("explanation"),
            "generate prompt must mention the optional explanation parameter",
        )
    }

    @Test
    fun `buildGeneratePrompt explains what the explanation should cover and its language`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("portable zip") || prompt.contains("shortcuts"),
            "explanation note should give packaging-decision examples",
        )
        assertTrue(
            prompt.contains("language"),
            "explanation note must require the user's language",
        )
    }

    @Test
    fun `buildGeneratePrompt states result is required and explanation optional`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("required parameter result plus optional explanation"),
            "Finalize section must describe the two-parameter contract, got:\n$prompt",
        )
    }

    @Test
    fun `buildGeneratePrompt shows a two-parameter CORRECT finalize example`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("explanation=\""),
            "CORRECT example must show explanation as an independent string parameter, got:\n$prompt",
        )
    }

    @Test
    fun `buildGeneratePrompt warns against putting the explanation inside result`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("explanation") && prompt.contains("separate parameter"),
            "WRONG list must forbid embedding the explanation inside the result manifest JSON, got:\n$prompt",
        )
    }

    @Test
    fun `buildGeneratePrompt caps the explanation at 3 short sentences and about 60 words`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("3 short sentences"),
            "generate prompt must cap the explanation at 3 short sentences, got:\n$prompt",
        )
        assertTrue(
            prompt.contains("60 words"),
            "generate prompt must give a word budget of about 60 words, got:\n$prompt",
        )
    }

    @Test
    fun `buildGeneratePrompt forbids a field-by-field recap in the explanation`() {
        val prompt = buildGeneratePrompt(skeleton = "{}", analyze = minimalAnalyze(), outputPath = null)

        assertTrue(
            prompt.contains("Do NOT recap") && prompt.contains("field-by-field"),
            "generate prompt must forbid recapping extract_dir/bin/shortcuts/persist field-by-field, got:\n$prompt",
        )
    }
}
