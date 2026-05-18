# Verkada Pass Door Access API

## Summary

The app exposes a straightforward authenticated API path for listing doors and remotely opening them. This is the best starting point for a widget client.

## Door list endpoint

The unlockables request is:

```text
POST https://vcerberus.command.verkada.com/access/v2/user/pass/unlockables/1
```

Request body:

```json
{
  "organizationId": "<organization id>"
}
```

This is implemented through:

- `GetAccessPointsRoute`
- `GetAccessPointsRequest`
- `AppAccessPointsApi.fetchAccessPoints(...)`

The decompiled API client confirms this request uses **POST**.

## Door list response shape

The response model contains `unlockables`, which include access-point groupings such as:

- `doors`
- `elevators`

The same response also includes `userSchedules`.

The `Door` model includes fields such as:

- `doorId`
- `name`
- `accessControllerId`
- `floorId`
- `deviceIds`
- `readerPeripherals`

The `UserSchedule` model includes:

- `doorId`
- `startDateTime`
- `endDateTime`
- `events`

Each `Event` includes:

- `doorPermissionState`
- `startDateTime`
- `endDateTime`

That is enough to build a door picker or persist a selected door for a widget action.

## Remote unlock endpoint

The remote open request is:

```text
POST https://vcerberus.command.verkada.com/access/v2/user/virtual_device/{accessPointId}/unlock
```

Route parameter:

- `accessPointId`

Request body:

```json
{
  "unlockMethod": "mobile"
}
```

Response body:

```json
{
  "duration": <number>
}
```

The decompiled API client confirms this request also uses **POST**.

## Required auth

The app sends the same authenticated REST headers used elsewhere:

```text
X-Verkada-Organization-Id: <organizationId>
X-Verkada-User-Id: <userId>
X-Verkada-Auth: <userToken>
```

For normal REST requests, the WSS-specific `Origin` and `Cookie` behavior does not appear to be required.

## Remote unlock vs BLE

The APK treats remote unlock and Bluetooth unlock as **separate capabilities**.

`UnlockPermissionsModel` is built in `zp\o0.java` as:

- `isBluetoothUnlockAccessAllowed = org.bleUnlockEnabled && user.bluetoothAccessAllowed`
- `isRemoteUnlockAllowed = user.mobileAccessAllowed`

That means the app does **not** derive remote unlock eligibility from the BLE peripheral handshake.

`BleService` is the foreground service that runs the BLE central + peripheral unlock stack, but it is started separately from the remote HTTP unlock call path. The remote unlock API caller still posts directly to:

```text
POST /access/v2/user/virtual_device/{accessPointId}/unlock
```

There is no APK evidence that this request waits for a nearby BLE conversation before it is allowed to execute.

## 403 troubleshooting notes

A `403 Forbidden` on the remote unlock endpoint is more likely tied to account or server-side authorization than to the BLE peripheral path.

APK-backed reasons to consider first:

1. `user.mobileAccessAllowed` may be false for this profile.
2. The server may enforce additional org-side policy even though the client-side derived permission model only exposes `mobileAccessAllowed` for remote unlock.
3. The selected door may be visible in the unlockables list but still reject the remote unlock action for this user/session.
4. The unlockables payload may show a returned `userSchedules` entry for that `doorId`, and its `events[].doorPermissionState` window is worth checking during a `403`.
5. Auth/session values may be valid enough for listing but not acceptable for the unlock request if a required server-side condition is missing.

Live proof from this session:

1. `GET https://vcerberus.command.verkada.com/user/{organizationId}` returned `accessMethods` with `BLUETOOTH: true` and `MOBILE: false`.
2. `POST https://vcerberus.command.verkada.com/organization/config/get` returned `api-unlock-enabled = false` and `ble-unlock-enabled = true`.
3. The tested door still showed `scheduleStates=ALLOW`.

That combination explains the reproduced `403 {"id":"4968","message":"No permission to unlock."}`:

- BLE unlock is enabled for this org/user.
- Remote/mobile unlock is disabled for this org/user.
- The `403` is not evidence that BLE proximity must succeed first.

## Related endpoints found

Static analysis also surfaced:

```text
https://vcerberus.command.verkada.com/organization/config/get
https://vcerberus.command.verkada.com/door/{doorId}/metrics
```

Intercom endpoints also exist in the app, but they were not the main target for this work.

## Practical widget flow

1. Authenticate and persist credentials.
2. Call the unlockables endpoint.
3. Let the user choose a door and store the `accessPointId`.
4. Call the remote unlock endpoint from a widget tap.

## Confidence note

The endpoint, verb, route parameter, and request/response model shape came from the APK analysis.

The strongest APK-backed unlock-method evidence currently found is:

- `com\verkada\android\library\access\network\requests\UnlockMethod.java`
  - `@SerialName("mobile")`
  - `UnlockMethod.MOBILE`
- `com\verkada\android\library\access\network\requests\UnlockDoorRequest.java`
  - default `unlockMethod = UnlockMethod.MOBILE`

That means the APK itself clearly contains a mobile unlock method serialized as **`"mobile"`**.

There is still one nuance:

- the Pass app's specific `RemoteUnlockRequest` model is a raw string wrapper, and the exact string literal assignment was not recovered from the Pass remote-unlock call site itself
- however, the APK evidence is now stronger for **`"mobile"`** than the earlier external-only guess of `"web"`

Everything else needed for the remote unlock path is grounded in the APK:

- endpoint
- verb
- route parameter
- request model shape
- response model shape
- required auth headers
