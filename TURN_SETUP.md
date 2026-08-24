# Cloudflare TURN deployment

The Android app obtains short-lived TURN credentials from the authenticated Firebase callable
function `getTurnCredentials`. The permanent Cloudflare TURN token must never be placed in the
Android project, source control, or a chat message.

## One-time setup

1. Delete the Cloudflare TURN key whose token was exposed, then create a new TURN key. This
   produces a new App ID and API token.
2. Upgrade the Firebase project to the Blaze plan if Cloud Functions deployment requests it.
3. Sign in with the Firebase CLI:

   ```powershell
   npx firebase-tools@latest login
   ```
4. Store the TURN key ID when prompted:

   ```powershell
   npx firebase-tools@latest functions:secrets:set CLOUDFLARE_TURN_KEY_ID --project travelbuddy-e7367
   ```

5. Store the new TURN API token when prompted:

   ```powershell
   npx firebase-tools@latest functions:secrets:set CLOUDFLARE_TURN_API_TOKEN --project travelbuddy-e7367
   ```

6. Install and deploy the backend:

   ```powershell
   npx firebase-tools@latest deploy --only functions:getTurnCredentials --project travelbuddy-e7367
   ```

Never add either value to a Gradle file, `google-services.json`, or Kotlin source.
