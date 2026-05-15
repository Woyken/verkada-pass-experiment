# External Reference Review

## Summary

The external projects were useful, but only one of them materially helped with the Pass/mobile work.

## 1. bepsoccer/verkadaModule

This was the most useful reference.

### What it confirmed

For the same remote unlock endpoint:

```text
POST https://vcerberus.command.verkada.com/access/v2/user/virtual_device/{doorId}/unlock
```

the PowerShell module sends:

```json
{
  "unlockMethod": "web"
}
```

That showed an alternate remote-unlock caller using **`web`**, but later APK-only evidence pointed to a stronger mobile-app value of **`mobile`**.

### What else it shows

It uses a broader Command web-session model that is different from the Pass app's magic-link flow:

- login via `POST https://vprovision.command.verkada.com/user/login`
- receives `userToken`, `csrfToken`, and `userId`
- uses cookies such as `auth`, `org`, `token`, and `usr`
- often sends `x-verkada-token` and `X-Verkada-Auth`

This is useful as a reference for Command private APIs, but it should not be confused with the Pass app's mobile auth contract.

## Practical takeaway

The external references do **not** replace the APK findings, but they do add one important confirmed detail:

- external Command tooling may send `unlockMethod = "web"`
- the APK itself contains `UnlockMethod.MOBILE` serialized as **`"mobile"`**

Everything else still points to the same recommendation:

- use the Pass/mobile auth findings for an end-user widget client
- use the remote unlock API first
- treat BLE as a later, separate implementation
