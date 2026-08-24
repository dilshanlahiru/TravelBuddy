const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {defineSecret} = require("firebase-functions/params");

const turnKeyId = defineSecret("CLOUDFLARE_TURN_KEY_ID");
const turnApiToken = defineSecret("CLOUDFLARE_TURN_API_TOKEN");

exports.getTurnCredentials = onCall(
    {
      region: "asia-southeast1",
      secrets: [turnKeyId, turnApiToken],
      timeoutSeconds: 15,
    },
    async (request) => {
      if (!request.auth) {
        throw new HttpsError("unauthenticated", "Sign in before joining a room.");
      }

      const response = await fetch(
          `https://rtc.live.cloudflare.com/v1/turn/keys/${turnKeyId.value()}` +
          "/credentials/generate-ice-servers",
          {
            method: "POST",
            headers: {
              Authorization: `Bearer ${turnApiToken.value()}`,
              "Content-Type": "application/json",
            },
            body: JSON.stringify({ttl: 86400}),
          },
      );

      if (!response.ok) {
        throw new HttpsError(
            "unavailable",
            "The relay service could not issue credentials.",
        );
      }

      const payload = await response.json();
      if (!Array.isArray(payload.iceServers)) {
        throw new HttpsError("internal", "The relay service returned invalid data.");
      }
      return {iceServers: payload.iceServers};
    },
);
