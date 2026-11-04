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
| Unit | `.\tools\run-unit-tests.ps1` | Every change (no Domino) |
| Must-pass smoke | `.\tools\smoke-kyber-api.ps1` | Before merge / after install |
| Extended smoke | `.\tools\smoke-kyber-api.ps1 -Extended` | Nightly / pre-release (heal probes) |

Unit tests run without a live Domino server. Live smoke needs DRAPI and a Kyber-enabled NSF.

**Logging:** Kyber uses `java.util.logging` under `se.infoware.domino.kyber` (see `KyberLog`). INFO covers create/update/delete/import/ensure/compile; WARNING covers soft failures and repairs. Enable FINE on that logger for per-element import detail. Filter Domino/KEEP console on that name.

## Pull requests

`main` requires a green **verify** check. Open a branch and a PR.

1. Keep the change focused.
2. Run `.\build.ps1` in `extension` when you can.
3. Keep `extension/pom.xml` version in sync with the version tables in `README.md` and `extension/README.md`, and with `info.version` in `openapi/openapi.kyber.json` (CI `verify` checks all four).
4. After merge, tag `vX.Y.Z`, write release notes, and attach `infoware-kyber-X.Y.Z.jar` built on a machine with `DRAPI_HOME` (release JAR upload is still manual; CI only verifies metadata).

## Related

- MCP adapter: [Aldhani](https://github.com/infoware-se/aldhani)
- Security reports: [SECURITY.md](SECURITY.md)
