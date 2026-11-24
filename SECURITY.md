# Security Policy

DHVON is an offline-first Android media player with no accounts and no servers. That keeps the security surface small and specific: how the app reads your media, how it handles files other apps hand to it, and the decoders it bundles.

## Supported versions

Only the **latest release** receives security fixes. If you're on an older version, please update first.

| Version | Supported |
|---|---|
| Latest release | ✅ |
| Anything older | ❌ |

## Reporting a vulnerability

**Please don't open a public issue, pull request or discussion for a vulnerability.**

Report it privately instead:

1. Go to **[Report a vulnerability](https://github.com/rhshourav/dhvon/security/advisories/new)** (the repository's **Security** tab → **Report a vulnerability**).
2. Describe the problem and include:
   - the DHVON version, Android version and device
   - clear steps to reproduce, or a proof of concept
   - what an attacker could do with it
   - if a media or subtitle file triggers it, a minimal file you have the right to share

## What to expect

DHVON is maintained by one person, so these are goals rather than guarantees:

- an acknowledgement within **7 days**
- an assessment and next steps within **14 days**
- a fix in a new release as soon as is practical, with critical issues first
- credit in the advisory and release notes, if you'd like it

Please keep the details private until a fix is released, and for no longer than **90 days** after you report. If a report is declined, you'll get a reason.

## Scope

**In scope**

- Reading or deleting files outside what you granted DHVON access to
- Getting around Android's consent dialogs for deleting files
- Intent filters and exported components (such as “Open with”) that another app could abuse
- Crafted media or subtitle files that run code, leak data, or crash DHVON in a way that suggests memory corruption
- A bundled library (Media3, FFmpeg, libVLC) with a known vulnerability that affects DHVON
- Sensitive data written to logs or backups
- Weaknesses in the release workflow or the project website

**Out of scope**

- Issues that need a rooted or modified device, or physical access to an unlocked phone
- Social engineering, or modified and unofficial builds of DHVON
- A file that simply doesn't play. That's a [bug report](https://github.com/rhshourav/dhvon/issues/new/choose)
- Vulnerabilities in an upstream library with no DHVON-specific impact. Please report those upstream too, and DHVON will pick up the fix

There is no bug bounty program.

## Safe harbor

If you make a good-faith effort to follow this policy, avoid harming other people's data or devices, and give us reasonable time to fix the issue, we won't take legal action against you for your research.

## Staying safe as a user

- Download DHVON only from the official [GitHub Releases](https://github.com/rhshourav/dhvon/releases).
- DHVON never asks for all-files access. A build that does isn't the real DHVON.
- Keep Android up to date, and update DHVON when a new release lands.
