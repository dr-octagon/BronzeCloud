# BronzeCloud domain pilot automation

This directory contains the restricted command used by the Raspberry Pi Hermes
agent after a DiziPal domain-change notification.

The command deliberately supports only `DiziPal` and `DiziPalOriginal`. It reads
the replacement URL from the monitor state, revalidates the destination, changes
only the provider `mainUrl` and `build.gradle.kts` version, pushes `master`, waits
for the CloudStream build workflow, and verifies the published manifest/package.

```bash
python automation/bronzecloud-domain.py status
python automation/bronzecloud-domain.py verify DiziPalOriginal
python automation/bronzecloud-domain.py apply DiziPal
python automation/bronzecloud-domain.py apply DiziPalOriginal
python automation/bronzecloud-domain.py rollback DiziPal
```

A rollback restores the previous domain but still increments the plugin version,
so CloudStream clients receive it as a new update.
