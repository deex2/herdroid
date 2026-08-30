package dev.herdroid.core.herdr

import dev.herdroid.core.model.RemoteOperatingSystem
import java.util.Base64

object RemoteCommands {
    fun posix(vararg arguments: String): String = arguments.joinToString(" ") { "'${it.replace("'", "'\"'\"'")}'" }

    fun powerShell(command: String): String =
        "powershell -NoProfile -NonInteractive -EncodedCommand " +
            Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_16LE))

    fun resolveHerdr(os: RemoteOperatingSystem): String = when (os) {
        RemoteOperatingSystem.LINUX -> "sh -lc ${posix("command -v herdr")}"
        RemoteOperatingSystem.WINDOWS -> powerShell("(Get-Command herdr -ErrorAction SilentlyContinue).Source")
    }

    fun home(os: RemoteOperatingSystem): String = when (os) {
        RemoteOperatingSystem.LINUX -> "sh -lc ${posix("printf %s \$HOME")}"
        RemoteOperatingSystem.WINDOWS -> powerShell("\$env:USERPROFILE")
    }

    fun bootstrap(os: RemoteOperatingSystem, herdrPath: String, pluginVersion: String): String = when (os) {
        RemoteOperatingSystem.LINUX -> {
            val script = """
                set -e
                home=${'$'}HOME
                arch=${'$'}(uname -m)
                version=${'$'}(${posix(herdrPath, "--version")})
                sessions=${'$'}(${posix(herdrPath, "session", "list", "--json")})
                plugins=${'$'}(${posix(herdrPath, "plugin", "list", "--json")})
                case ${'$'}arch in
                  x86_64) target=${BridgeArtifactCatalog.LINUX_X64} ;;
                  aarch64) target=${BridgeArtifactCatalog.LINUX_ARM64} ;;
                  *) target=unsupported ;;
                esac
                root="${'$'}home/.herdroid/plugins/${BridgeArtifactCatalog.PLUGIN_ID}/$pluginVersion/${'$'}target"
                sha() { [ ! -f "${'$'}1" ] && return; hash=${'$'}(sha256sum -- "${'$'}1") || exit; set -- ${'$'}hash; printf %s "${'$'}1"; }
                manifest=${'$'}(sha "${'$'}root/herdr-plugin.toml")
                binary=${'$'}(sha "${'$'}root/bin/herdroid-bridge")
                printf '%s\0%s\0%s\0%s\0%s\0%s\0%s' "${'$'}home" "${'$'}arch" "${'$'}version" "${'$'}sessions" "${'$'}plugins" "${'$'}manifest" "${'$'}binary"
            """.trimIndent()
            "sh -lc ${posix(script)}"
        }
        RemoteOperatingSystem.WINDOWS -> powerShell(
            "function Run([string[]]\$a){\$o=& ${powerShellLiteral(herdrPath)} @a;if(\$LASTEXITCODE -ne 0){exit \$LASTEXITCODE};\$o -join \"`n\"};" +
                "function Hash([string]\$p){if([IO.File]::Exists(\$p)){[BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([IO.File]::ReadAllBytes(\$p))).Replace('-','').ToLowerInvariant()}};" +
                "\$home=\$env:USERPROFILE;\$processor=\$env:PROCESSOR_ARCHITECTURE;\$arch=\"windows|\$processor\";" +
                "\$target=if(\$processor -in @('AMD64','x86_64')){'${BridgeArtifactCatalog.WINDOWS_X64}'}else{'unsupported'};" +
                "\$root=\"\$home/.herdroid/plugins/${BridgeArtifactCatalog.PLUGIN_ID}/$pluginVersion/\$target\";" +
                "\$fields=@(\$home,\$arch,(Run @('--version')),(Run @('session','list','--json')),(Run @('plugin','list','--json')),(Hash \"\$root/herdr-plugin.toml\"),(Hash \"\$root/bin/herdroid-bridge.exe\"));" +
                "[Console]::Out.Write((\$fields -join [char]0))",
        )
    }

    fun herdr(os: RemoteOperatingSystem, path: String, vararg arguments: String): String = when (os) {
        RemoteOperatingSystem.LINUX -> posix(path, *arguments)
        RemoteOperatingSystem.WINDOWS -> powerShell("& ${powerShellLiteral(path)} ${arguments.joinToString(" ") { powerShellLiteral(it) }}")
    }

    fun bridge(os: RemoteOperatingSystem, binary: String, herdrPath: String): String =
        herdr(os, binary, "--stdio", "--herdr-bin", herdrPath)

    fun makeDirectory(os: RemoteOperatingSystem, path: String): String = when (os) {
        RemoteOperatingSystem.LINUX -> "mkdir -p ${posix(path)}"
        RemoteOperatingSystem.WINDOWS -> powerShell("[IO.Directory]::CreateDirectory(${powerShellLiteral(path)}) | Out-Null")
    }

    fun sha256(os: RemoteOperatingSystem, path: String): String = when (os) {
        RemoteOperatingSystem.LINUX ->
            "hash=\$(${posix("sha256sum", "--", path)}) || exit; set -- \$hash; printf %s \"\$1\""
        RemoteOperatingSystem.WINDOWS -> powerShell(
            "[BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash(" +
                "[IO.File]::ReadAllBytes(${powerShellLiteral(path)}))).Replace('-','').ToLowerInvariant()",
        )
    }

    private fun powerShellLiteral(value: String) = "'${value.replace("'", "''")}'"
}
