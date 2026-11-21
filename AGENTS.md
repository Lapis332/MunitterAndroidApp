# Munitter development rules

## Shared service inventory

Before investigating Munitter service names, purposes, account/resource IDs, Production/Development, related projects, or configuration locations, read the latest [canonical private service inventory](https://github.com/Lapis332/MunitterWebApplication/blob/master/MunitterWebApplication/Docs/SERVICE_ACCOUNTS.md). Shared service rules are in the [Web root entry](https://github.com/Lapis332/MunitterWebApplication/blob/master/AGENTS.md) and the service-inventory section of the [Web app entry](https://github.com/Lapis332/MunitterWebApplication/blob/master/MunitterWebApplication/AGENTS.md). Keep the existing Android hash, signing, build, device, and release rules.

- Reuse recorded information; investigate only missing or conflicting items through the inventory's configuration index and sources. Distinguish confirmed facts, configuration definitions, and unconfirmed items. Do not infer IDs or environments, or confuse a shared parent account with shared resources or credentials.
- The private Git inventory is the single source of truth across Windows, VM, Production, and Mac. Do not create a separate local inventory or copy its private account metadata into this public Android repository.
- When configuration changes, update the canonical inventory in the same work with purpose, account/resource ID, environment, related project, configuration location, confirmation date, and evidence through the existing commit/push/PR/master process. Never record secrets.
- Changes to Production services, provider settings, credentials, DNS, Access, or public routes require explicit user authorization for the target and change. Ordinary app-release instructions do not provide blanket authorization for service changes; adding these references does not authorize deployment, app distribution, or provider sends.

## Long-term hash standard

BLAKE3 is the first choice and formal standard for new Munitter-owned file integrity checks, content addressing/deduplication, backup checksums, build artifact hashes, Harness evidence manifests/source attestations, cache keys, and large-data verification. This is a continuing development rule, not a temporary optimization. Use a 256-bit (32-byte) digest; the normal hexadecimal representation is 64 lowercase characters.

- Stream file input with bounded memory. Use the implementation's supported parallel mode when useful, and verify that it produces the same digest as serial/streaming input.
- Store an explicit schema/version and algorithm identifier with the digest, for example `{"schema":"munitter-digest/v1","algorithm":"blake3-256","value":"<64 lowercase hexadecimal characters>"}`. That example contains a placeholder, not a real digest. Use `blake3-256` and `value` for new ecosystem digest records. Reject unsupported algorithms; never infer the algorithm from digest length.
- Ordinary unkeyed BLAKE3, keyed BLAKE3, and BLAKE3 key derivation are separate modes. Do not substitute one for another. Define a stable versioned domain and unambiguous input encoding for purpose-specific hashes; record mode/context separately when it affects verification. Do not invent a keyed scheme to replace an established authentication protocol.
- Never use BLAKE3 directly for password storage. Preserve the approved password-specific KDF and its cost/salt policy.

Keep required SHA-256 (or another required algorithm) for Git/GitHub internals, external APIs, TLS/certificates/digital signatures, NuGet/npm/Gradle and other package integrity formats, Cloudflare/other service contracts, FIPS or other compliance requirements, password/authentication schemes, and existing formal evidence/signature/audit contracts that require it. An exception is scoped to its actual contract, not to every use in that repository.

## Compatibility and review

- Do not introduce SHA-256 for a new independent Munitter hash. A necessary new use must add an exception entry to [the hash inventory](docs/HASH_STANDARD.md), with classification, owner/contract, reason, producer, verifier, and removal/review condition. Include this check in review; the inventory is not an automatic CI gate.
- Trace both the writer and reader before migration. Update them together; retain legacy SHA-256 reading/verification until migration completion, with dual verification where the contract needs it. New-format writes use BLAKE3. Never relabel an existing SHA-256 value as BLAKE3.
- Preserve original formal evidence and distinguish historical PASS evidence from new BLAKE3 evidence. Do not recalculate/rewrite existing databases or backups without separate authorization. Keep failure handling and formal quality gates at least as strong.
- Prefer a maintained existing implementation with a reviewed license, dependencies, vulnerability status, official BLAKE3 vectors, and platform support. No paid license, additional billing, large dependency solely for sharing, or handwritten cryptographic primitive. Validate empty/short input, official vectors, large files, streaming and parallel equivalence on each supported platform; report unexecuted platforms accurately.
- Work on an independent branch and follow the repository's established checks. Policy changes do not authorize installation, device mutation, Development disruption, Production deployment, disabling security gates, or weakening branch protection.

See [the repository inventory and migration plan](docs/HASH_STANDARD.md) for current exceptions and pending work.
