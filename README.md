# RealmsExorcist

**Understand your server. Fix problems carefully.**

RealmsExorcist is a diagnostics and controlled-maintenance plugin for Paper and compatible server software. It is designed for administrators who need useful findings without destructive automated changes.

## Features

- Server software, Minecraft version, Java runtime, player, world and heap report.
- Required and optional plugin dependency checks, including disabled required dependencies.
- Plugin API metadata checks against the server's declared API version.
- Duplicate command-label detection from plugin metadata.
- Recursive YAML syntax validation with file, line and column diagnostics.
- Safe YAML parsing: arbitrary Java object construction is disabled.
- File-size and file-count limits to avoid unbounded scans.
- Recent log analysis grouped by normalized error signatures.
- Advisory Paper/Spigot proxy configuration checks that never print forwarding secrets.
- JVM heap reporting with an explicit warning that heap usage is process-wide, not attributable to individual plugins.
- Explicitly confirmed recovery of this plugin's own config, with a timestamped backup first.
- Configurable checks and scan limits.

## Commands

| Command | Description |
| --- | --- |
| `/exorcist` | Show help |
| `/exorcist scan` | Run all enabled diagnostics |
| `/exorcist scan plugins` | Check plugin dependencies and state |
| `/exorcist scan configs` | Validate YAML files in the plugins directory |
| `/exorcist scan compatibility` | Check declared API versions and duplicate command labels |
| `/exorcist scan proxy` | Inspect common Paper/Spigot proxy configuration indicators |
| `/exorcist logs` | Group common failures in the recent server log |
| `/exorcist doctor` | Show a concise server runtime report |
| `/exorcist repair own-config` | Show the supported recovery action |
| `/exorcist repair own-config confirm` | Back up and restore this plugin's default config |
| `/exorcist reload` | Reload this plugin's config |

Aliases: `/rex`, `/exorcism`.

## Permissions

- `realmsexorcist.use`: diagnostics, default OP.
- `realmsexorcist.admin`: reload and confirmed recovery, default OP.

## Requirements

- Paper or a compatible fork such as Purpur.
- Java 21 or newer.
- Minecraft 1.21.4 or newer within the supported Paper API line used to compile this release.

## Build

Run:

```bash
mvn --batch-mode clean verify
```

The shaded plugin jar is written to `target/RealmsExorcist-1.1.0.jar`. GitHub Actions runs the same Maven verification on pushes and pull requests.

## Safety and limitations

- Diagnostics do not modify other plugins or their configuration files.
- YAML checks parse files without constructing arbitrary Java objects. They do not guarantee that a plugin accepts every value semantically.
- Compatibility checks use plugin metadata. They cannot prove that a plugin is compatible or identify every runtime conflict.
- Duplicate command labels are reported as possible conflicts; aliases and runtime command registrations can differ.
- Proxy checks are advisory and do not validate network topology, forwarding secrets, firewall rules or proxy reachability.
- Log output can contain private data. Review it before sharing.
- Heap usage is JVM-wide and cannot be accurately assigned to individual plugins by this plugin.
- Recovery currently supports only RealmsExorcist's own config. It creates a backup before replacing that file. It never repairs or rewrites another plugin's files.

## Project direction

Future work can add deeper Paper/Velocity/Bungee diagnostics, richer stack-trace grouping, compatibility data maintained against explicit version sources, and additional narrowly scoped backup-first recovery operations. Any repair affecting another plugin must remain opt-in, previewable and backed up before changes.
