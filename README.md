# RealmsExorcist 👻

**Your server is possessed. We have Java.**

A read-only Paper/Purpur diagnostic plugin. The branding is unserious; the diagnostics are not.

## Features
- Server implementation, Minecraft version and Java runtime report.
- Missing hard and optional plugin dependency checks.
- Detects installed plugins that are disabled.
- JVM memory usage (explicitly JVM-wide, never falsely attributed to individual plugins).
- Top-level YAML configuration inventory.
- Scans the tail of logs/latest.log for common exception and plugin-load failures.
- Configurable startup scan and log limits.

## Commands
- /exorcist - help
- /exorcist scan - inspect plugins, runtime and configs
- /exorcist logs - inspect recent log errors
- /exorcist doctor - runtime report
- /exorcist reload - reload this plugin's config
- Aliases: /rex, /exorcism

## Requirements
- Paper or compatible fork such as Purpur
- Java 21+
- Minecraft 1.20+ (compiled against Paper 1.21.4 API)

## Build
Run mvn clean package. The jar is written to target/RealmsExorcist-1.0.0.jar.

## Safety
This release is intentionally read-only. It does not delete, overwrite, disable, or reload other plugins. It does not yet parse YAML syntax or claim to identify per-plugin memory usage. Log output may contain sensitive information; review it before sharing.

## Roadmap
- YAML syntax validation with file and line diagnostics.
- Version-aware plugin compatibility and conflict checks.
- Backup-first repair actions with explicit confirmation.
- Grouped stack-trace summaries.
- Velocity and BungeeCord diagnostics.
