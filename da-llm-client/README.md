# da-llm-client

Shared label-provider library (`ILLMClient`, `LLMClientFactory`) used by `da-autolabel`
and `da-policyengine`. A plain library with no web server. Spring Boot auto-configuration
registers every provider bean and the factory (`LlmClientAutoConfiguration`).

## Providers

| Provider type | Bean | What it is | Needs |
|---|---|---|---|
| `openai` | `openAiLLMClient` | OpenAI chat model | API key |
| `ollama` | `ollamaLLMClient` | Local or self-hosted model via Ollama | a reachable Ollama daemon |
| `rules` | `rulesLLMClient` | Deterministic keyword and pattern rules | nothing |
| `mock` | `mockLLMClient` | Placeholder labels unrelated to the asset | `dlap.autolabel.allow-mock=true` (job path, see below) |

Provider types are case-insensitive. `da-autolabel` picks the provider with
`dlap.autolabel.llm-provider` (pipeline path) or the ML configuration's `providerType`
(job path). `llm.provider` (policy drafting) accepts only `openai` and `ollama`: a rule set
and the mock cannot complete a free-form prompt, so they throw `UnsupportedOperationException`
from `complete()` and never resolve for that path.

## The `rules` provider

`RuleBasedLabelClient` is the honest offline provider for demos and air-gapped installs. It
runs no model and makes no network call.

- **Deterministic.** The same input gives the same suggestions in the same order, on every
  run and every JVM. No randomness, no clock in the result.
- **Named.** Every suggestion carries `ruleId` and a reasoning string that starts with
  `Rule <id>: <description>`. When several rules propose the same label, the highest
  confidence wins (earliest rule on a tie) and the others are listed as `also matched`.
- **Stamped as a rule match.** Every suggestion carries `source = SMART_LABEL_RULE`
  (`RuleBasedLabelClient.ASSIGNMENT_SOURCE`). A consumer that honors
  `LabelSuggestion.getSource()` records a deterministic match, not a model inference.
- **Content-free.** Reasoning reports the rule's own keyword, or the word `pattern`. It never
  echoes asset text, so a matched SSN or key id does not reach logs or the review queue.

### Input

One text block of `Key: value` lines, as built by the auto-label pipeline:

```
Asset name: customer_profiles
Cloud provider: GCP
Asset type: BIGQUERY_TABLE
Region: us-central1
Existing labels: ANALYTICS
Business metadata: {owner=data-team}
```

Name keys fill the name field. `Path`, `Location`, `URI` and `Qualified name` fill the path
field; a name that contains `/`, `\`, `:` or `.` is also the path. `Business metadata` and
everything after it fill the metadata field. `Cloud provider`, `Asset type`, `Region` and
`Existing labels` are ignored, so a rule never matches its own earlier output. Text with none
of these keys is treated as a bare asset name.

Keyword rules match whole tokens after splitting camel case and separators, with an optional
plural `s`: `customerPII`, `Customer_PII-export.csv` and `customer pii` all contain the token
`pii`, while `catalog` does not contain `log`.

### Shipped rule set (`rules-v1`)

| Label | Category | Confidence | Evidence |
|---|---|---|---|
| PII | Sensitivity | 0.80 to 0.95 | explicit marker, government id words, SSN-shaped value, contact words (name and path only), HR words |
| PHI | Compliance | 0.88 to 0.95 | explicit marker, clinical words |
| FINANCIAL | Compliance | 0.82 to 0.92 | payment card and bank words, invoice, ledger and payroll words |
| CONFIDENTIAL | Classification | 0.90 to 0.98 | marker words, credential words, key-file names, embedded access key id or private key header |
| CUSTOMER_DATA, SALES_DATA, MARKETING_DATA, INVENTORY_DATA | Business Domain | 0.85 | domain vocabulary in the name or path |
| ANALYTICS | Business Domain | 0.65 | weak signal, below the auto-labeler's 0.70 default, so it goes to human review |
| ML_TRAINING, LOG_DATA | Data Type | 0.85 to 0.88 | domain vocabulary in the name or path |

`DefaultLabelRules` is the authoritative list. Limits that are part of the contract:

- Rules read the asset's name, path and metadata. They never read row content, so a match
  means the naming says so, not that data was inspected.
- The set never proposes `PUBLIC` or `INTERNAL` (a keyword is not evidence that data is safe
  to expose) and never proposes `ARCHIVED` (lifecycle state owned by the policy engine). Rules
  can only escalate.
- Changing a rule, a confidence or a label changes labeling results: bump
  `DefaultLabelRules.VERSION`.

### Custom rules

Declare your own `RuleBasedLabelClient` bean (the auto-configured one backs off):

```java
@Bean
RuleBasedLabelClient rulesLLMClient() {
    return new RuleBasedLabelClient(
            List.of(LabelRule.keywords("acme.contracts", "CONFIDENTIAL", "Classification", 0.9,
                    LabelRule.NAME_AND_PATH, "Named for contracts", "contract", "msa", "nda")),
            "acme-v1", Clock.systemDefaultZone());
}
```

## The `mock` provider

`MockLLMClient` returns labels chosen from a fixed list by a hash of the input text. It is
reproducible but unrelated to the asset, and its reasoning says so. Use `rules` when a demo
needs labels that can be explained.

The opt-in flag `dlap.autolabel.allow-mock` (default `false`) is enforced on the job path
(`LLMIntegrationServiceImpl`), which also refuses to fall back to the mock silently. The
Kafka pipeline path (`AutoLabelOrchestratorImpl`) selects its provider with
`dlap.autolabel.llm-provider` and does not check the flag, so `llm-provider=mock` works there
without it.
