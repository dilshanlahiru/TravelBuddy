const {randomInt} = require("node:crypto");
const {URL} = require("node:url");
const {initializeApp} = require("firebase-admin/app");
const {getDatabase} = require("firebase-admin/database");
const {FieldValue, getFirestore} = require("firebase-admin/firestore");
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {defineSecret} = require("firebase-functions/params");

initializeApp();

const firestore = getFirestore();
const realtimeDatabase = getDatabase();
const turnKeyId = defineSecret("CLOUDFLARE_TURN_KEY_ID");
const turnApiToken = defineSecret("CLOUDFLARE_TURN_API_TOKEN");
const openRouteServiceApiKey = defineSecret("OPENROUTESERVICE_API_KEY");

const REGION = "asia-southeast1";
const MAX_ROOM_MEMBERS = 6;
const MAX_CREATE_ATTEMPTS = 5;

function requireAuth(request) {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Sign in before accessing a room.");
  }
  return request.auth;
}

function requireRoomId(data) {
  const roomId = typeof data?.roomId === "string" ? data.roomId.trim() : "";
  if (!/^\d{6}$/.test(roomId)) {
    throw new HttpsError("invalid-argument", "Enter a valid six-digit room code.");
  }
  return roomId;
}

function requireCoordinate(point, fieldName) {
  if (!point || typeof point.latitude !== "number" ||
      typeof point.longitude !== "number" ||
      !Number.isFinite(point.latitude) || !Number.isFinite(point.longitude) ||
      point.latitude < -90 || point.latitude > 90 ||
      point.longitude < -180 || point.longitude > 180) {
    throw new HttpsError("invalid-argument", `${fieldName} coordinates are invalid.`);
  }
  return {latitude: point.latitude, longitude: point.longitude};
}

async function requireActiveRoomOwner(roomId, uid) {
  const snapshot = await firestore.collection("rooms").doc(roomId).get();
  if (!snapshot.exists || snapshot.data().status !== "ACTIVE") {
    throw new HttpsError("failed-precondition", "This room is not active.");
  }
  const room = snapshot.data();
  if (room.createdBy !== uid) {
    throw new HttpsError("permission-denied", "Only the room creator can change the route.");
  }
  return room;
}

function memberValues(auth) {
  return {
    uid: auth.uid,
    displayName: typeof auth.token.name === "string" && auth.token.name.trim() ?
      auth.token.name.trim() : "Travel Buddy User",
    photoUrl: typeof auth.token.picture === "string" ? auth.token.picture : null,
    joinedAt: FieldValue.serverTimestamp(),
    isConnected: false,
    isSpeaking: false,
    micMode: "PTT",
  };
}

function roomResponse(roomId, room) {
  return {
    roomId,
    roomCode: room.roomCode || roomId,
    createdBy: room.createdBy,
    status: room.status,
    memberCount: room.memberCount,
  };
}

async function grantRoomAccess(roomId, ownerUid, uid) {
  await realtimeDatabase.ref().update({
    [`room_access/${roomId}/ownerUid`]: ownerUid,
    [`room_access/${roomId}/members/${uid}`]: true,
  });
}

async function revokeRoomAccess(roomId, uid, roomClosed) {
  if (roomClosed) {
    await realtimeDatabase.ref().update({
      [`room_access/${roomId}`]: null,
      [`room_status/${roomId}`]: null,
      [`navigation_status/${roomId}`]: null,
      [`signaling/${roomId}`]: null,
      [`group_trips/${roomId}`]: null,
    });
    return;
  }
  await realtimeDatabase.ref().update({
    [`room_access/${roomId}/members/${uid}`]: null,
    [`room_status/${roomId}/members/${uid}`]: null,
    [`navigation_status/${roomId}/members/${uid}`]: null,
    [`group_trips/${roomId}/locations/${uid}`]: null,
  });
}

exports.createRoom = onCall({region: REGION}, async (request) => {
  const auth = requireAuth(request);
  for (let attempt = 0; attempt < MAX_CREATE_ATTEMPTS; attempt += 1) {
    const roomId = randomInt(100000, 1000000).toString();
    const roomRef = firestore.collection("rooms").doc(roomId);
    const memberRef = roomRef.collection("members").doc(auth.uid);
    const room = {
      roomId,
      roomCode: roomId,
      createdBy: auth.uid,
      createdAt: FieldValue.serverTimestamp(),
      status: "ACTIVE",
      memberCount: 1,
    };
    try {
      await firestore.runTransaction(async (transaction) => {
        if ((await transaction.get(roomRef)).exists) {
          throw new RoomCodeCollisionError();
        }
        transaction.set(roomRef, room);
        transaction.set(memberRef, memberValues(auth));
      });
      try {
        await grantRoomAccess(roomId, auth.uid, auth.uid);
      } catch (error) {
        try {
          const cleanup = firestore.batch();
          cleanup.delete(memberRef);
          cleanup.delete(roomRef);
          await cleanup.commit();
        } catch (cleanupError) {
          console.error("Room creation cleanup failed", cleanupError);
        }
        throw error;
      }
      return roomResponse(roomId, room);
    } catch (error) {
      if (error instanceof RoomCodeCollisionError) continue;
      throw toHttpsError(error, "The room could not be created.");
    }
  }
  throw new HttpsError("resource-exhausted", "A unique room code could not be created.");
});

exports.joinRoom = onCall({region: REGION}, async (request) => {
  const auth = requireAuth(request);
  const roomId = requireRoomId(request.data);
  const roomRef = firestore.collection("rooms").doc(roomId);
  const memberRef = roomRef.collection("members").doc(auth.uid);

  let room;
  try {
    room = await firestore.runTransaction(async (transaction) => {
      const roomSnapshot = await transaction.get(roomRef);
      if (!roomSnapshot.exists) {
        throw new HttpsError("not-found", "No active room was found for that code.");
      }
      const currentRoom = roomSnapshot.data();
      if (currentRoom.status !== "ACTIVE") {
        throw new HttpsError("failed-precondition", "This room is closed.");
      }

      const memberSnapshot = await transaction.get(memberRef);
      if (!memberSnapshot.exists) {
        if ((currentRoom.memberCount || 0) >= MAX_ROOM_MEMBERS) {
          throw new HttpsError("resource-exhausted", "This room already has six people.");
        }
        transaction.set(memberRef, memberValues(auth));
        transaction.update(roomRef, {memberCount: FieldValue.increment(1)});
        currentRoom.memberCount = (currentRoom.memberCount || 0) + 1;
      } else {
        const member = memberValues(auth);
        transaction.set(memberRef, {
          displayName: member.displayName,
          photoUrl: member.photoUrl,
        }, {merge: true});
      }
      return currentRoom;
    });
    await grantRoomAccess(roomId, room.createdBy, auth.uid);
    return roomResponse(roomId, room);
  } catch (error) {
    throw toHttpsError(error, "The room could not be joined.");
  }
});

exports.leaveRoom = onCall({region: REGION}, async (request) => {
  const auth = requireAuth(request);
  const roomId = requireRoomId(request.data);
  const roomRef = firestore.collection("rooms").doc(roomId);
  const memberRef = roomRef.collection("members").doc(auth.uid);

  try {
    const outcome = await firestore.runTransaction(async (transaction) => {
      const roomSnapshot = await transaction.get(roomRef);
      if (!roomSnapshot.exists) return {roomClosed: true};
      const room = roomSnapshot.data();
      if (room.createdBy === auth.uid) {
        const memberSnapshot = await transaction.get(roomRef.collection("members"));
        transaction.update(roomRef, {memberCount: 0, status: "CLOSED"});
        memberSnapshot.docs.forEach((member) => transaction.delete(member.ref));
        return {roomClosed: true};
      }
      const memberSnapshot = await transaction.get(memberRef);
      if (!memberSnapshot.exists) return {roomClosed: room.status !== "ACTIVE"};

      const nextCount = Math.max(0, (room.memberCount || 1) - 1);
      transaction.delete(memberRef);
      if (nextCount === 0) {
        transaction.update(roomRef, {memberCount: 0, status: "CLOSED"});
        return {roomClosed: true};
      }
      transaction.update(roomRef, {memberCount: nextCount});
      return {roomClosed: false};
    });
    await revokeRoomAccess(roomId, auth.uid, outcome.roomClosed);
    return outcome;
  } catch (error) {
    throw toHttpsError(error, "The room could not be left.");
  }
});

exports.searchDestinations = onCall(
    {
      region: REGION,
      secrets: [openRouteServiceApiKey],
      timeoutSeconds: 15,
    },
    async (request) => {
      const auth = requireAuth(request);
      const roomId = requireRoomId(request.data);
      const query = typeof request.data?.query === "string" ?
        request.data.query.trim() : "";
      if (query.length < 3 || query.length > 120) {
        throw new HttpsError(
            "invalid-argument",
            "Enter at least three characters to search.",
        );
      }

      try {
        await requireActiveRoomOwner(roomId, auth.uid);
        const url = new URL("https://api.openrouteservice.org/geocode/search");
        url.searchParams.set("api_key", openRouteServiceApiKey.value());
        url.searchParams.set("text", query);
        url.searchParams.set("size", "6");
        const focus = request.data?.focus;
        if (focus != null) {
          const point = requireCoordinate(focus, "Search focus");
          url.searchParams.set("focus.point.lat", point.latitude.toString());
          url.searchParams.set("focus.point.lon", point.longitude.toString());
        }

        const response = await fetch(url, {
          headers: {Accept: "application/json"},
          signal: AbortSignal.timeout(12000),
        });
        if (!response.ok) {
          console.error("Geocoding provider error", response.status);
          throw new HttpsError(
              "unavailable",
              "Destination search is temporarily unavailable.",
          );
        }
        const payload = await response.json();
        const results = Array.isArray(payload.features) ? payload.features : [];
        return {
          results: results.slice(0, 6).map((feature) => {
            const coordinates = feature?.geometry?.coordinates;
            const properties = feature?.properties || {};
            const label = typeof properties.label === "string" ?
              properties.label.trim() : "";
            if (!label || label.length > 300 ||
                !Array.isArray(coordinates) || coordinates.length < 2 ||
                !Number.isFinite(coordinates[0]) ||
                !Number.isFinite(coordinates[1]) ||
                coordinates[0] < -180 || coordinates[0] > 180 ||
                coordinates[1] < -90 || coordinates[1] > 90) {
              return null;
            }
            return {
              label,
              latitude: coordinates[1],
              longitude: coordinates[0],
            };
          }).filter(Boolean),
        };
      } catch (error) {
        throw toHttpsError(error, "Destination search failed.");
      }
    },
);

exports.closeRoom = onCall({region: REGION}, async (request) => {
  const auth = requireAuth(request);
  const roomId = requireRoomId(request.data);
  const roomRef = firestore.collection("rooms").doc(roomId);

  try {
    const members = await firestore.runTransaction(async (transaction) => {
      const roomSnapshot = await transaction.get(roomRef);
      if (!roomSnapshot.exists) return [];
      const room = roomSnapshot.data();
      if (room.createdBy !== auth.uid) {
        throw new HttpsError("permission-denied", "Only the room creator can close it.");
      }
      const memberSnapshot = await transaction.get(roomRef.collection("members"));
      transaction.update(roomRef, {memberCount: 0, status: "CLOSED"});
      memberSnapshot.docs.forEach((member) => transaction.delete(member.ref));
      return memberSnapshot.docs.map((member) => member.id);
    });
    await revokeRoomAccess(roomId, auth.uid, true);
    return {roomClosed: true, removedMemberCount: members.length};
  } catch (error) {
    throw toHttpsError(error, "The room could not be closed.");
  }
});

exports.setGroupRoute = onCall(
    {
      region: REGION,
      secrets: [openRouteServiceApiKey],
      timeoutSeconds: 30,
    },
    async (request) => {
      const auth = requireAuth(request);
      const roomId = requireRoomId(request.data);
      const origin = requireCoordinate(request.data?.origin, "Origin");
      const destination = requireCoordinate(request.data?.destination, "Destination");

      try {
        await requireActiveRoomOwner(roomId, auth.uid);
        const response = await fetch(
            "https://api.heigit.org/openrouteservice/v2/directions/" +
            "driving-car/geojson",
            {
              method: "POST",
              headers: {
                Authorization: openRouteServiceApiKey.value(),
                "Content-Type": "application/json",
                Accept: "application/geo+json",
              },
              body: JSON.stringify({
                coordinates: [
                  [origin.longitude, origin.latitude],
                  [destination.longitude, destination.latitude],
                ],
                geometry_simplify: false,
                instructions: true,
                instructions_format: "text",
                language: "en",
              }),
              signal: AbortSignal.timeout(25000),
            },
        );
        if (!response.ok) {
          console.error("Routing provider error", response.status);
          throw new HttpsError(
              "unavailable",
              "The routing provider could not calculate this route.",
          );
        }

        const payload = await response.json();
        const feature = payload.features?.[0];
        const coordinates = feature?.geometry?.coordinates;
        const summary = feature?.properties?.summary;
        const segments = feature?.properties?.segments;
        if (!Array.isArray(coordinates) || coordinates.length < 2 ||
            coordinates.length > 10000 ||
            typeof summary?.distance !== "number" ||
            typeof summary?.duration !== "number" ||
            !Array.isArray(segments) || segments.length === 0) {
          throw new HttpsError("data-loss", "The routing provider returned invalid data.");
        }
        const geometry = coordinates.map((coordinate) => {
          if (!Array.isArray(coordinate) || coordinate.length < 2 ||
              !Number.isFinite(coordinate[0]) || !Number.isFinite(coordinate[1])) {
            throw new HttpsError("data-loss", "The route geometry is invalid.");
          }
          return {latitude: coordinate[1], longitude: coordinate[0]};
        });
        const instructions = [];
        for (const segment of segments) {
          if (!Array.isArray(segment?.steps)) {
            throw new HttpsError(
                "data-loss",
                "The routing provider returned invalid directions.",
            );
          }
          for (const step of segment.steps) {
            const wayPoints = step?.way_points;
            const instruction = typeof step?.instruction === "string" ?
              step.instruction.trim() : "";
            if (!instruction || instruction.length > 300 ||
                !Array.isArray(wayPoints) || wayPoints.length !== 2 ||
                !wayPoints.every(Number.isInteger) ||
                wayPoints[0] < 0 || wayPoints[1] < wayPoints[0] ||
                wayPoints[1] >= geometry.length ||
                !Number.isFinite(step.distance) || step.distance < 0 ||
                !Number.isFinite(step.duration) || step.duration < 0 ||
                !Number.isInteger(step.type)) {
              throw new HttpsError(
                  "data-loss",
                  "The routing provider returned invalid directions.",
              );
            }
            instructions.push({
              instruction,
              distanceMeters: step.distance,
              durationSeconds: step.duration,
              type: step.type,
              startGeometryIndex: wayPoints[0],
              endGeometryIndex: wayPoints[1],
            });
          }
        }
        if (instructions.length === 0 || instructions.length > 2000) {
          throw new HttpsError(
              "data-loss",
              "The routing provider returned invalid directions.",
          );
        }

        await requireActiveRoomOwner(roomId, auth.uid);
        const route = {
          originLatitude: origin.latitude,
          originLongitude: origin.longitude,
          destinationLatitude: destination.latitude,
          destinationLongitude: destination.longitude,
          distanceMeters: summary.distance,
          durationSeconds: summary.duration,
          geometry,
          instructions,
          revision: Date.now(),
          updatedBy: auth.uid,
        };
        await realtimeDatabase.ref(`group_trips/${roomId}/route`).set(route);
        return {
          distanceMeters: route.distanceMeters,
          durationSeconds: route.durationSeconds,
          revision: route.revision,
        };
      } catch (error) {
        throw toHttpsError(error, "The route could not be shared.");
      }
    },
);

exports.clearGroupRoute = onCall({region: REGION}, async (request) => {
  const auth = requireAuth(request);
  const roomId = requireRoomId(request.data);
  try {
    await requireActiveRoomOwner(roomId, auth.uid);
    await realtimeDatabase.ref(`group_trips/${roomId}/route`).remove();
    return {cleared: true};
  } catch (error) {
    throw toHttpsError(error, "The route could not be cleared.");
  }
});

exports.getTurnCredentials = onCall(
    {
      region: REGION,
      secrets: [turnKeyId, turnApiToken],
      timeoutSeconds: 15,
    },
    async (request) => {
      requireAuth(request);

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

function toHttpsError(error, fallbackMessage) {
  if (error instanceof HttpsError) return error;
  console.error(error);
  return new HttpsError("internal", fallbackMessage);
}

class RoomCodeCollisionError extends Error {}
