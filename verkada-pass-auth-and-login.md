# Verkada Pass Auth and Login

## Scope

These notes describe the login and session behavior needed to reproduce an authorized client for `com.verkada.VerkadaPass`.

APK analyzed:

- Package: `com.verkada.VerkadaPass`
- Version: `3.5.6`

## Login initiation

The app sends the magic-link request to:

```text
POST https://vauth.command.verkada.com/auth/magic
```

Request model:

```json
{
  "email": "<user email>",
  "organizationShortName": "<optional org short name>",
  "tokenScopeTypes": ["PASS_APP"]
}
```

The default token scope in the app is `PASS_APP`.

## Deep links accepted by the app

The manifest and navigation code show these relevant entry points:

```text
verkada-pass://magic-link
verkada-pass://reset-password
https://command.verkada.com/magic-login/redeem
https://command.verkada.com/mobile-app/magic-login/redeem
```

The app's internal magic-link route pattern is:

```text
verkada-pass://magic-link?userEmail={userEmail}&orgShortName={orgShortName}&magicToken={magicToken}&entityId={entityId}&shard={shard}
```

Arguments extracted from the deep link:

- `userEmail`
- `orgShortName`
- `magicToken`
- `entityId`
- `shard`

## Magic-link redemption

The login coroutine ultimately calls the auth layer with:

- email
- org short name
- magic token
- token scope `PASS_APP`
- one boolean feature flag from app state

The important part for an interoperable client is that the redemption flow is driven by:

- `email`
- `orgShortName`
- `magicToken`
- `PASS_APP`

`entityId` appears in the deep-link arguments but was not proven to be required by the actual redeem call.

## Credential model

Successful auth yields a credential model with these fields:

- `organizationId`
- `userId`
- `email`
- `userToken`
- `userShard`

For a third-party client, the critical persisted values are:

- `organizationId`
- `userId`
- `userToken`
- `userShard`

## Authenticated request contract

For normal authenticated REST requests, the app injects:

```text
X-Verkada-Organization-Id: <organizationId>
X-Verkada-User-Id: <userId>
X-Verkada-Auth: <userToken>
```

The client also rewrites `command.verkada.com` to the active shard host when shard context exists.

## Cookie and Origin behavior

The app also adds:

```text
Origin: https://command.verkada.com
Cookie: auth=<userToken>; usr=<userId>
```

but that behavior applies to **WSS** requests, not standard REST calls.

For `vglobal.global-prod.verkada.com`, auth headers and cookies are explicitly removed.

## Other relevant auth-related endpoints

Static analysis also found:

```text
https://vprovision.command.verkada.com/core/v1/user/auth/challenges
https://vprovision.command.verkada.com/user/login
https://vauth.command.verkada.com/auth/tokeninfo
https://vauth.command.verkada.com/saml/access_token
https://vglobal.global-prod.verkada.com/shard/get_for_email
https://vglobal.global-prod.verkada.com/org/validate_short_name
https://vglobal.global-prod.verkada.com/user/send_list_of_orgs
```

The app clearly uses shard-aware auth, but no refresh-token contract was proven from the decompiled model used for the Pass app flow.

## Minimum client flow

1. Request a magic link through `POST /auth/magic`.
2. Extract `userEmail`, `orgShortName`, `magicToken`, and `shard` from the link that ultimately reaches the app.
3. Redeem the magic token through the same auth path the mobile app uses.
4. Persist `organizationId`, `userId`, `userToken`, and `userShard`.
5. Send authenticated Pass API requests with the three `X-Verkada-*` headers above.

## Remaining auth gaps

- The exact live redirect/translation path from `access.command.verkada.com/pass-app/magic-link?...` into the app deep link was not captured.
- A live session would be the cleanest way to confirm any runtime-only auth nuances that JADX did not expose cleanly.
