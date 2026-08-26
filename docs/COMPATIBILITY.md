# Compatibility

DSH Mobile speaks the DeepSeek Harness **web client protocol** (the JSON-RPC
surface the harness GUI itself consumes over `/api`). That protocol is
internal to the harness (it is not versioned on the wire), so this app pins a
protocol baseline and verifies the harness version from `host.describe`.

| DSH Mobile | Harness version | Status |
|---|---|---|
| 0.1.0 | 0.1.0-rc.5 | Supported baseline |

## Version policy

- On connect the app reads `host.describe.version` and compares it with the
  baseline. A different version shows a non-blocking warning.
- If a call fails with an unrecognized shape the app degrades gracefully
  (unknown event types and unknown tool cards render as generic entries; the
  wire JSON is parsed leniently).
- New harness releases are validated with the fixture capture tool
  (`tools/capture`) and the compatibility table above is updated.

## Privileged surfaces (by harness design)

These methods are **privileged**: the harness serves them to loopback
clients by default, and to *any* reachable client only when it is started
with `--allow-privileged-remote` (which sets
`servePrivilegedToTrustedHosts`). Without the flag a LAN client receives
HTTP 403 (surfaced as the `forbidden` error code); when the harness composes
no such service at all the call 404s (`capability-unavailable`).

- `settings.*`, `credentials.*`, `llm.discoverModels`
- `host.pickDirectory`, `host.openPath`
- agent-preset authoring (`agentPreset.read/copy/openDocument/remove`)

The app's **harness settings** screens (General / Models / Plugins / Agent
presets, under app Settings → Harness) drive these methods. Their behavior
follows the plane's answer:

- **Writable** (the flag is on, or the client is loopback): full editing —
  settings writes are CAS-guarded by the namespace revision and retry once
  on a `settings-conflict`; API keys travel only through
  `credentials.set` (write-only) and are judged client-side before the
  write.
- **Refused** (403/404): the screens degrade to read-only. The
  *non-privileged* reads keep working and are rendered: the provider
  directory (`llm.providers`), the model catalog (`llm.models`), the preset
  roster (`agentPreset.list`), and the plugin inventory
  (`pluginInventory/list`).

The non-privileged reads above are available to every LAN client with or
without the flag, so a refused harness still shows what its providers serve
and which presets exist — it just cannot be edited from the phone.
