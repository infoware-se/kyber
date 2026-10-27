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

Unit tests run without a live Domino server. Live smoke needs DRAPI and a Kyber-enabled NSF.

## Pull requests

`main` requires a green **verify** check. Open a branch and a PR.

1. Keep the change focused.
2. Run `.\build.ps1` in `extension` when you can.
3. Keep `extension/pom.xml` version and the README version table in sync.
4. After merge, tag `vX.Y.Z`, write release notes, and attach `infoware-kyber-X.Y.Z.jar` built on a machine with `DRAPI_HOME` (release JAR upload is still manual; CI only verifies metadata).

## Related

- MCP adapter: [Aldhani](https://github.com/infoware-se/aldhani)
- Security reports: [SECURITY.md](SECURITY.md)
