# plugin-resourcepacks

The Grounds Velocity PackSet delivery plugin. It caches validated PackSet snapshots locally and
delivers an ordered Adventure resource-pack request on player login.

## Requirements

Install [`plugin-config`](https://github.com/groundsgg/plugin-config) alongside this plugin. It
supplies `ConfigDefinition` at runtime and owns the authenticated configuration snapshot,
reconciliation, and change-notification boundary.

Set `GROUNDS_ENVIRONMENT` to the deployment environment name, for example `stage`. The value is
required, must be non-blank, and must not contain whitespace, `/`, or `\\`. It selects this exact
configuration scope:

```text
network/<env>/resourcepacks/global
```

The default document seeds the Stable Grounds PackSet only when no operator document exists:

```json
{
  "schemaVersion": 1,
  "enabled": true,
  "source": {
    "baseUrl": "https://cdn.grounds.gg",
    "packSet": "grounds-global",
    "channel": "stable"
  },
  "required": true,
  "prompt": "Grounds benötigt seine Resourcepacks."
}
```

For Stage, an operator changes only `source.channel` to `edge`; the deployment environment and
PackSet channel are separate choices.

## Runtime boundary

The Velocity runtime reads an immutable in-memory snapshot during player login: login does no
synchronous configuration-service or CDN I/O. The client performs refreshes on its own scheduler
and caches validated CDN state under the plugin data directory at `packset-cache` (normally
`plugins/plugin-resourcepacks/packset-cache`).

An arbitrary HTTPS origin is an administrator capability. Until service-config has application-level
administrator authorization, all configuration writes must remain inside the current
authenticated/private service-config boundary. Do not expose this document for public or
unauthenticated writes.

## Runtime behavior

The plugin sends one ordered Adventure request through `Player.sendResourcePacks`, preserving each
pack's UUID, URI and SHA-1 together with the configured prompt and required flag. It sends only a
source-matched READY snapshot or source-matched DEGRADED fallback, suppresses duplicate
fingerprints per player, resends after a packset or delivery-setting change, and removes that record
on disconnect. A source change reconfigures the same client; it never offers the retained fallback
from the old source.

`NOT_READY` configuration does not apply defaults, create a CDN client, or send packs. The first
valid configuration change starts exactly one client. Shutdown detaches plugin-owned listeners and
closes that client. Local runtime diagnostics expose client status, current and fallback
fingerprints, and requested/accepted/downloaded/failed/declined counters with credential values
redacted from logged reasons.

## Current scope

There is no Minestom adapter and no gamemode or per-server PackSet overlay support yet.
