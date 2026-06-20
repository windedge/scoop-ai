package com.scoopai.core.artifacts

import java.io.File

/**
 * Debug artifacts for a single generation run.
 *
 * Writes phase outputs (research/analyze/generate/refine) to
 * `<baseDir>/.scoopai/runs/<runId>/` directory. All writes use atomic
 * temp-file + rename to avoid half-written files.
 *
 * @param runId Unique identifier for this run.
 * @param baseDir Base directory under which `.scoopai/runs/` is created.
 *   Defaults to the current working directory.
 */
class RunArtifacts(
    val runId: String,
    private val baseDir: String = ".",
) {
    /** Full path to the run directory. */
    val runDir: File get() = File(baseDir, ".scoopai/runs/$runId")

    /**
     * Write a phase's output to `<runDir>/<phaseName>.json`.
     *
     * Uses atomic write: content is first written to a temp file
     * in the same directory, then renamed to the final name.
     */
    fun writePhase(phaseName: String, content: String) {
        atomicWrite("$phaseName.json", content)
    }

    /**
     * Mark the run as completed by writing a `done.marker` file.
     */
    fun finalize() {
        atomicWrite("done.marker", "completed")
    }

    /**
     * Mark the run as failed by writing an `error.txt` file.
     */
    fun markFailed(error: String) {
        atomicWrite("error.txt", error)
    }

    /**
     * Atomically write content to a file in the run directory.
     *
     * Writes to a temp file first, then renames to the target name.
     * On platforms where renameTo fails when target exists (Windows),
     * the target is deleted before rename, or a copy+delete fallback is used.
     */
    private fun atomicWrite(fileName: String, content: String) {
        val dir = runDir
        dir.mkdirs()
        val target = File(dir, fileName)
        val temp = File(dir, ".${fileName}.tmp")
        try {
            temp.writeText(content)
            if (target.exists()) {
                target.delete()
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            if (temp.exists()) temp.delete()
            throw e
        }
    }
}
