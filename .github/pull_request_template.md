## Summary

<!-- What changed and why (English). -->

## Checklist

- [ ] `extension\build.ps1` passes locally when the change touches Java or the API
- [ ] For a release: `.\tools\release-gate.ps1` (or `-SkipSmoke`) before tag / JAR upload
- [ ] For a release: `extension/pom.xml` version matches `README.md`, `extension/README.md`, and OpenAPI `info.version` (`.\tools\check-version-sync.ps1`)
- [ ] README / extension docs updated when routes or install steps change
- [ ] No tokens, passwords, or `.env` secrets in the diff
- [ ] No Aldhani/Yavin deploy or MCP policy changes in this repo (wrong product)

## Test plan

- [ ] 
