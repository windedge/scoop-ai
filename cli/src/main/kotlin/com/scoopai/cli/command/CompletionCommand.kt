package com.scoopai.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.choice

/**
 * Generate shell completion scripts for scoop-ai.
 *
 * Supports bash, zsh, fish, and powershell.
 * Output the script and source it in your shell:
 *   scoop-ai completion bash > /usr/local/share/bash-completion/completions/scoop-ai
 *   scoop-ai completion powershell >> ${'$'}PROFILE
 */
class CompletionCommand : CliktCommand(
    name = "completion",
    help = "Generate shell completion scripts",
) {
    val shell: String by argument("shell")
        .choice("bash", "zsh", "fish", "powershell")

    override fun run() {
        val parent = currentContext.parent?.command
            ?: run {
                echo("Error: completion must be run as a subcommand of scoop-ai", err = true)
                throw ProgramResult(1)
            }

        val script = generateScript(shell, parent)
        echo(script.trimEnd())
    }

    companion object {
        fun generateScript(shell: String, command: CliktCommand): String {
            return when (shell) {
                "bash" -> generateBashCompletion()
                "zsh" -> generateZshCompletion()
                "fish" -> generateFishCompletion()
                "powershell" -> generatePowerShellCompletion()
                else -> error("Unsupported shell: $shell")
            }
        }

        // ---------------------------------------------------------------
        // Bash completion
        // ---------------------------------------------------------------
        private fun generateBashCompletion(): String {
            return """# scoop-ai bash completion
# Source: scoop-ai completion bash

_scoop_ai_completions() {
    local cur prev words cword
    _init_completion -n ":" || return

    local opts="--help --version"
    local subcommands="generate doctor config completion"

    local subcmd=""
    for ((i=1; i<${'$'}cword; i++)); do
        case "${'$'}{words[i]}" in
            generate|doctor|config|completion)
                subcmd="${'$'}{words[i]}"
                break
                ;;
        esac
    done

    case "${'$'}subcmd" in
        generate)
            opts="-o --output -P --provider -m --model --base-url --api-key --github-token --app-name -p --prompt -s --persist -V --verify -n --non-interactive --no-cache --debug --no-progress --help"
            ;;
        doctor)
            opts="--help"
            ;;
        config)
            opts="show --help"
            ;;
        completion)
            opts="bash zsh fish powershell --help"
            ;;
        *)
            opts="${'$'}subcommands ${'$'}opts"
            ;;
    esac

    COMPREPLY=($(compgen -W "${'$'}opts" -- "${'$'}cur"))
}

complete -F _scoop_ai_completions scoop-ai
""".trimStart()
        }

        // ---------------------------------------------------------------
        // Zsh completion
        // ---------------------------------------------------------------
        private fun generateZshCompletion(): String {
            return """#compdef scoop-ai
# Source: scoop-ai completion zsh

_scoop_ai() {
    local context state state_descr line
    typeset -A opt_args

    _arguments -C \
        '--help[show help]' \
        '--version[show version]' \
        '1: :->cmd' \
        '*:: :->args'

    case "${'$'}state" in
        cmd)
            _describe 'command' (
                'generate:Generate a Scoop manifest from a GitHub URL'
                'doctor:Run environment diagnostics'
                'config:Manage runtime configuration'
                'completion:Generate shell completion scripts'
            )
            ;;
        args)
            case "${'$'}line[1]" in
                generate)
                    _arguments \
                        '-o[output file]:file:_files' \
                        '--output[output file]:file:_files' \
                        '-P[LLM provider]:provider:(deepseek openai anthropic ollama openrouter openai-compatible)' \
                        '--provider[LLM provider]:provider:(deepseek openai anthropic ollama openrouter openai-compatible)' \
                        '-m[model name]:model:' \
                        '--model[model name]:model:' \
                        '--base-url[API base URL]:url:' \
                        '--api-key[API key]:key:' \
                        '--github-token[GitHub token]:token:' \
                        '--app-name[application name]:name:' \
                        '-p[additional prompt]:prompt:' \
                        '--prompt[additional prompt]:prompt:' \
                        '-s[persist mode]:mode:(auto force none)' \
                        '--persist[persist mode]:mode:(auto force none)' \
                        '-V[verify manifest]' \
                        '--verify[verify manifest]' \
                        '-n[non-interactive mode]' \
                        '--non-interactive[non-interactive mode]' \
                        '--no-cache[disable cache]' \
                        '--debug[enable debug]' \
                        '--no-progress[disable progress]' \
                        '--help[show help]'
                    ;;
                doctor)
                    _arguments '--help[show help]'
                    ;;
                config)
                    _arguments \
                        'show:Show runtime configuration' \
                        '--help[show help]'
                    ;;
                completion)
                    _arguments \
                        '1:shell:(bash zsh fish powershell)' \
                        '--help[show help]'
                    ;;
            esac
            ;;
    esac
}

_scoop_ai
""".trimStart()
        }

        // ---------------------------------------------------------------
        // Fish completion
        // ---------------------------------------------------------------
        private fun generateFishCompletion(): String {
            return """# scoop-ai fish completion
# Source: scoop-ai completion fish

complete -c scoop-ai -f -n '__fish_use_subcommand' -l 'help' -d 'Show help'
complete -c scoop-ai -f -n '__fish_use_subcommand' -l 'version' -d 'Show version'

complete -c scoop-ai -f -n '__fish_use_subcommand' -a 'generate' -d 'Generate a Scoop manifest from a GitHub URL'
complete -c scoop-ai -f -n '__fish_use_subcommand' -a 'doctor' -d 'Run environment diagnostics'
complete -c scoop-ai -f -n '__fish_use_subcommand' -a 'config' -d 'Manage runtime configuration'
complete -c scoop-ai -f -n '__fish_use_subcommand' -a 'completion' -d 'Generate shell completion scripts'

complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -s 'o' -l 'output' -d 'Output file path' -r
complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -s 'P' -l 'provider' -d 'LLM provider' -x -a 'deepseek openai anthropic ollama openrouter openai-compatible'
complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -s 'm' -l 'model' -d 'Model name' -r
complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -l 'base-url' -d 'API base URL' -r
complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -l 'api-key' -d 'API key' -r
complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -l 'github-token' -d 'GitHub token' -r
complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -l 'app-name' -d 'Application name' -r
complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -s 'p' -l 'prompt' -d 'Additional prompt' -r
complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -s 's' -l 'persist' -d 'Persist mode' -x -a 'auto force none'
complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -s 'V' -l 'verify' -d 'Verify manifest'
complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -s 'n' -l 'non-interactive' -d 'Non-interactive mode'
complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -l 'no-cache' -d 'Disable cache'
complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -l 'debug' -d 'Enable debug output'
complete -c scoop-ai -f -n '__fish_seen_subcommand_from generate' -l 'no-progress' -d 'Disable progress overlay'

complete -c scoop-ai -f -n '__fish_seen_subcommand_from doctor' -l 'help' -d 'Show help'

complete -c scoop-ai -f -n '__fish_seen_subcommand_from config' -a 'show' -d 'Show runtime configuration'
complete -c scoop-ai -f -n '__fish_seen_subcommand_from config' -l 'help' -d 'Show help'

complete -c scoop-ai -f -n '__fish_seen_subcommand_from completion' -a 'bash zsh fish powershell' -d 'Shell name'
complete -c scoop-ai -f -n '__fish_seen_subcommand_from completion' -l 'help' -d 'Show help'
""".trimStart()
        }

        // ---------------------------------------------------------------
        // PowerShell completion
        // ---------------------------------------------------------------
        private fun generatePowerShellCompletion(): String {
            return """# scoop-ai PowerShell completion
# Generated by 'scoop-ai completion powershell'
# Source this file in your PowerShell profile:
#   scoop-ai completion powershell >> ${'$'}PROFILE

Register-ArgumentCompleter -Native -CommandName scoop-ai -ScriptBlock {
    param(
        [string] ${'$'}wordToComplete,
        [string] ${'$'}commandName,
        [int]    ${'$'}cursorPosition
    )

    # Determine current subcommand
    ${'$'}parts = ${'$'}commandName.Split(' ', [StringSplitOptions]::RemoveEmptyEntries)
    ${'$'}subcommand = if (${'$'}parts.Length -gt 1) { ${'$'}parts[1] } else { ${'$'}null }

    # Top-level: subcommands + global flags
    if (-not ${'$'}subcommand) {
        ${'$'}top = @(
            'generate'
            'doctor'
            'config'
            'completion'
            '--help'
            '--version'
        )
        ${'$'}top | Where-Object { ${'$'}_ -match "^${'$'}(Regex.Escape(${'$'}wordToComplete))" }
        return
    }

    # Subcommand-specific completions
    switch (${'$'}subcommand) {
        'generate' {
            ${'$'}options = @(
                '-o'; '--output'
                '-P'; '--provider'
                '-m'; '--model'
                '--base-url'
                '--api-key'
                '--github-token'
                '--app-name'
                '-p'; '--prompt'
                '-s'; '--persist'
                '-V'; '--verify'
                '-n'; '--non-interactive'
                '--no-cache'
                '--debug'
                '--no-progress'
                '--help'
            )
            ${'$'}options | Where-Object { ${'$'}_ -match "^${'$'}(Regex.Escape(${'$'}wordToComplete))" }
        }
        'doctor' {
            @('--help') | Where-Object { ${'$'}_ -match "^${'$'}(Regex.Escape(${'$'}wordToComplete))" }
        }
        'config' {
            @('show'; '--help') | Where-Object { ${'$'}_ -match "^${'$'}(Regex.Escape(${'$'}wordToComplete))" }
        }
        'completion' {
            @('bash'; 'zsh'; 'fish'; 'powershell'; '--help') | Where-Object { ${'$'}_ -match "^${'$'}(Regex.Escape(${'$'}wordToComplete))" }
        }
    }
}
""".trimStart()
        }
    }
}
