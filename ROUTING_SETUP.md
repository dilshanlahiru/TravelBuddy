# Group routing setup

The Android map uses MapLibre with OpenFreeMap and does not require an API key.
Road routes are calculated by openrouteservice through Cloud Functions so the
routing key is never included in the Android app.

## One-time manual setup

1. Create or sign in to a HeiGIT/openrouteservice account at
   https://openrouteservice.org/dev/#/signup and create a Standard API key with
   Directions access.
2. From the project root, store the key as a Firebase secret. Enter the key only
   when the command prompts for it:

   ```powershell
   firebase functions:secrets:set OPENROUTESERVICE_API_KEY
   ```

3. Deploy the room/routing backend and security rules:

   ```powershell
   Set-Location functions
   npm run deploy
   ```

4. Build/install the latest Android app on each test device. The room creator
   and other members must grant foreground location permission when opening the
   group map.

## Live group locations

- Entering a room does not start voice chat or navigation. Each member joins
  and leaves those features independently from the room lobby.
- After a member explicitly joins navigation, a dedicated location foreground
  service continues sharing while the map is closed or the screen is off. An
  ongoing notification provides Open Map and Leave Navigation actions.
- Blue marks the current device; green marks other room members.
- The panel lists speeds for members currently visible on the map and the
  distance to the nearest member.
- Leaving navigation, leaving the room, or losing the Firebase connection
  removes that member's navigation presence and live-location record.
- The service must be started from a visible screen after foreground location
  permission is granted. It does not silently restart after Android kills it.

## Foreground route guidance

- Routes include openrouteservice turn instructions and geometry indexes.
- The map shows the current instruction, distance to its end, total remaining
  distance, and estimated remaining time.
- Navigation automatically follows the rider when a shared route starts. The
  camera uses the route bearing so the road ahead stays at the top, keeps the
  rider centered, tilts the map, and zooms out progressively as speed rises.
  Camera following can be paused when a member wants to inspect the full route.
- The room creator can search for a place or address using the server-side
  openrouteservice geocoder, or continue to select a destination by long press.
- Two consecutive location samples beyond the accuracy-adjusted route corridor
  mark the device as off route. Only the room creator can replace the shared
  route, using `Recalculate From Here`.
- Arrival is detected near the final route point.
- Voice prompts are not included yet.

The off-route and arrival behavior should be road-tested on two physical
devices before a production release. Mocked and stationary tests cannot fully
represent GPS drift, tunnels, weak reception, or parallel roads.

Do not put the openrouteservice key in `google-services.json`, source files,
Gradle properties, or a committed `.env` file.
