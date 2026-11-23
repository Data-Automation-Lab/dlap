# deployment/scripts

Helper scripts for local and test environments. The canonical stack is the dev compose pair
(`deployment/environments/dev/`); see the repository `README.md` for the quickstart.

| Script | Purpose |
|---|---|
| `dlap-master.sh`, `dlap-master.bat` | Build, start, stop and clean the services for the `dev`, `test` or `prod` environment. |
| `health-check-all-services.sh`, `health-check-all-services.bat` | Health report across the services (`--verbose`, `--json`, `--dry-run`). |
| `rbac-testing.sh`, `rbac-testing.ps1`, `rbac-testing-simple.ps1` | Role-based access checks against a running stack with the seeded dev realm users. |

The end-to-end harnesses live in `e2e-tests/`; run them through `e2e-tests/with-harness-lock.sh`.
