# Guide: Cloud-credential storage in production

DLAP needs credentials to reach a customer's cloud when it crawls/scans. How those
credentials are *held* is pluggable via one property - DLAP defines the contract
(`CredentialProvider`) and you choose the backing. See [ADR-0036](../DECISIONS.md).

## Pick a mode

```properties
# one of: encrypted-store (default) | secret-manager | federated
dlap.credentials.provider=federated
```

| Mode | DLAP stores | Use when |
|---|---|---|
| `encrypted-store` (default) | an **AES-256/GCM-encrypted** secret blob in its own DB | dev / single-tenant / no external manager |
| `secret-manager` | only a **reference** (`secretRef`); the secret lives in your manager | you run Vault / a cloud secret manager |
| `federated` | **nothing** | production - the strongest posture |

### `encrypted-store` (default)
Self-contained. Requires a 32-byte key:
```bash
export DLAP_ENCRYPTION_KEY="$(openssl rand -hex 32)"
```
The encrypted blob is `v2:` AES-256/GCM (a legacy `v1:` ECB blob is still readable and is
upgraded to GCM on the next write). Fine for dev; prefer one of the two below in prod.

### `secret-manager`
DLAP persists only a `secretRef` connection parameter (e.g.
`vault://secret/data/aws/prod#access`, `gcpsm://projects/p/secrets/s/versions/latest`,
`awssm://prod/dlap/aws`). Supply **one bean**, a `SecretResolver`, that turns a ref into
the secret:

```java
@Bean
SecretResolver vaultSecretResolver(VaultTemplate vault) {   // Spring Cloud Vault
    return ref -> vault.read(pathOf(ref)).getData().get(keyOf(ref)).toString();
}
```
Recommended OSS: **Spring Cloud Vault**, **Spring Cloud AWS Secrets Manager**,
**Spring Cloud GCP Secret Manager**, **Azure Spring Key Vault**. DLAP never persists or
logs the secret; it only holds the reference.

### `federated` (recommended for production)
DLAP stores no secret at all. It returns a *federated marker*; the caller authenticates
with the runtime's ambient identity, configured entirely in the platform:
- **AWS**: STS AssumeRole via an IRSA/instance role; put the role ARN in
  `connectionParameters.roleArn`.
- **GCP**: Workload Identity Federation; put the audience / service-account in
  `connectionParameters`.
- **Azure**: federated (workload) credentials.

No `DLAP_ENCRYPTION_KEY`, no stored secret, no rotation burden.

## The contract (what DLAP needs)

`CredentialProvider.resolve(Request) → Resolved`:
- **Request**: `connectionId`, `providerType`, non-secret `connectionParameters`
  (region, endpoint, `secretRef`, role ARN, audience…), and the encrypted blob (used only
  by `encrypted-store`).
- **Resolved**: either concrete `secretMaterial` (encrypted-store / secret-manager) or
  `isFederated()==true` (the caller uses the ambient identity).

To add a brand-new backing, implement `CredentialProvider` and register it as a bean; the
built-in modes select off `dlap.credentials.provider`, and your bean overrides them.

## Migration from the default

The sandbox stores AES-GCM blobs. To move to prod: choose `federated` (no data to migrate:
re-declare connections with role ARNs) or `secret-manager` (move each secret into your
manager, set `secretRef` on the connection, drop the encrypted blob). Rotate any
`DLAP_ENCRYPTION_KEY` that ever protected a real secret.
