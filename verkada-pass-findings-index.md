# Verkada Pass Reverse-Engineering Findings

This folder contains the implementation-focused findings from reversing `com.verkada.VerkadaPass` version `3.5.6`.

## Documents

- [verkada-pass-auth-and-login.md](verkada-pass-auth-and-login.md) - magic-link login flow, credential storage, auth headers, shard behavior
- [verkada-pass-door-access-api.md](verkada-pass-door-access-api.md) - door list and remote unlock API contract
- [verkada-pass-bluetooth.md](verkada-pass-bluetooth.md) - Bluetooth unlock architecture, permissions, BLE handshake details
- [verkada-external-references.md](verkada-external-references.md) - what the external Verkada projects confirmed and what was not relevant to Pass

## Recommended path for a widget app

Implement the **remote unlock** path first:

1. Request a magic link.
2. Redeem the magic link and persist `organizationId`, `userId`, `userToken`, and `userShard`.
3. Fetch unlockables.
4. Let the user pin one or more doors.
5. Call the remote unlock endpoint from the widget action.

Bluetooth unlock is significantly more complex and is better treated as a separate follow-up project.

## Remaining gaps

- The exact live redirect chain from `https://access.command.verkada.com/pass-app/magic-link?...` into the app deep link was not captured.
- The BLE central reader-serial-to-user mapping source was not fully proven.

Those gaps likely require a live capture with a real session.
