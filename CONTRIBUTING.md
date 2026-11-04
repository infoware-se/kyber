# Contributing

Comments, commit messages, and documentation are written in English.

## Development

- JDK 21, Maven 3.9+, and HCL Domino REST API (`DRAPI_HOME`) with the keep-* jars.

```powershell
cd extension
$env:DRAPI_HOME = "C:\Domino\restapi"
.\build.ps1
# optional install onto this machine:
.\build.ps1 -InstallToDrapi
```

**Tests (layered):**

| Layer | Command | When |
| --- | --- | --- |
| Version sync | `.\tools\check-version-sync.ps1` | CI `verify`; before release |
| Unit | `.\tools\run-unit-tests.ps1` | Every change (no Domino server; needs keep-* in local Maven) |
| Must-pass smoke | `.\tools\smoke-kyber-api.ps1` | Before merge / after install |
| Extended smoke | `.\tools\smoke-kyber-api.ps1 -Extended` | Nightly / pre-release (heal probes) |
| Release gate | `.\tools\release-gate.ps1` | Before tag + GitHub Release JAR |

Unit tests run without a live Domino server. Live smoke needs DRAPI and a Kyber-enabled NSF.

**Release gate** (`tools/release-gate.ps1`) is Kyber API quality only: version sync, no untracked `defaults/` resources, `mvn package`, copy `infoware-kyber-<version>.jar`, then Extended smoke. It does **not** call [Aldhani](https://github.com/infoware-se/aldhani) or [Yavin](https://github.com/infoware-se/yavin). Host install and post-deploy “what’s loaded?” smoke belong in Yavin.

```powershell
$env:DRAPI_HOME = "C:\Domino\restapi"
.\tools\release-gate.ps1
# unit + package only when Domino smoke is unavailable:
.\tools\release-gate.ps1 -SkipSmoke
```

**Logging:** Kyber uses `java.util.logging` under `se.infoware.domino.kyber` (see `KyberLog`). INFO covers create/update/delete/import/ensure/compile; WARNING covers soft failures and repairs. Enable FINE on that logger for per-element import detail. Filter Domino/KEEP console on that name.

## Pull requests

`main` requires a green **verify** check. Open a branch and a PR.

1. Keep the change focused.
2. Run `.\build.ps1` in `extension` when you can.
3. Keep `extension/pom.xml` version in sync with the version tables in `README.md` and `extension/README.md`, and with `info.version` in `openapi/openapi.kyber.json` (`.\tools\check-version-sync.ps1` / CI `verify`).
4. After merge, on a lab host with `DRAPI_HOME`: run `.\tools\release-gate.ps1`, then tag `vX.Y.Z`, write release notes, and `gh release create` with the gate’s `infoware-kyber-X.Y.Z.jar`.

**CI:** `verify` runs version sync on GitHub-hosted Ubuntu. Optional **unit** runs only when repository variable `KYBER_SELF_HOSTED_UNIT` is `true` on a self-hosted runner labeled `self-hosted`, `Windows`, `kyber` with `DRAPI_HOME` set. Smoke is not in Actions.

## Related

- MCP adapter: [Aldhani](https://github.com/infoware-se/aldhani) — tools, allowlist, write policy (not design routes)
- Deploy / ops: [Yavin](https://github.com/infoware-se/yavin) — host pins, install, post-deploy smoke (not design routes)
- Security reports: [SECURITY.md](SECURITY.md)
- Family boundary: [README — Boundary with Aldhani and Yavin](README.md#boundary-with-aldhani-and-yavin)
