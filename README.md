# plugin-resourcepacks

The Grounds Velocity PackSet delivery plugin. This initial scaffold defines the typed configuration
boundary only; runtime delivery is added in a later slice.

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

The upcoming Velocity runtime will read an immutable in-memory snapshot during player login: login
does no synchronous configuration-service or CDN I/O. Validated CDN state will be cached under the
plugin data directory at `packset-cache` (normally `plugins/resourcepacks/packset-cache`).

An arbitrary HTTPS origin is an administrator capability. Until service-config has application-level
administrator authorization, all configuration writes must remain inside the current
authenticated/private service-config boundary. Do not expose this document for public or
unauthenticated writes.

## Current scope

There is no Minestom adapter and no gamemode or per-server PackSet overlay support yet. This module
also does not download or deliver packs yet; it only supplies the typed settings consumed by the
future Velocity lifecycle.
