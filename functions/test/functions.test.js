const assert = require("node:assert/strict");
const {after, describe, it} = require("node:test");
const {deleteApp, initializeApp} = require("firebase-admin/app");
const {getDatabase} = require("firebase-admin/database");

const PROJECT_ID = "demo-travel-buddy";
const adminApp = initializeApp({
  projectId: PROJECT_ID,
  databaseURL: `https://${PROJECT_ID}.firebaseio.com`,
}, "functions-integration-tests");
const adminDatabase = getDatabase(adminApp);

after(async () => deleteApp(adminApp));

async function createClient(label) {
  const response = await fetch(
      "http://127.0.0.1:9099/identitytoolkit.googleapis.com/" +
      "v1/accounts:signUp?key=demo-api-key",
      {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          email: `${label}@example.test`,
          password: "test-password",
          returnSecureToken: true,
        }),
      },
  );
  assert.equal(response.ok, true);
  const payload = await response.json();
  return payload.idToken;
}

async function callFunction(idToken, name, data = {}) {
  const response = await fetch(
      `http://127.0.0.1:5001/${PROJECT_ID}/asia-southeast1/${name}`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${idToken}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({data}),
      },
  );
  const payload = await response.json();
  if (payload.error) {
    const error = new Error(payload.error.message);
    error.code = payload.error.status;
    throw error;
  }
  return payload.result || payload.data;
}

describe("room callable functions", () => {
  it("creates a room, enforces six members, and closes it when its owner leaves", async () => {
    const ownerToken = await createClient("owner");
    const createResult = await callFunction(ownerToken, "createRoom");
    const roomId = createResult.roomId;
    assert.match(roomId, /^\d{6}$/);
    assert.equal(createResult.memberCount, 1);

    let firstMemberToken;
    for (let index = 1; index <= 5; index += 1) {
      const memberToken = await createClient(`member${index}`);
      if (index === 1) firstMemberToken = memberToken;
      const joinResult = await callFunction(memberToken, "joinRoom", {roomId});
      assert.equal(joinResult.memberCount, index + 1);
    }

    const seventhToken = await createClient("seventh");
    await assert.rejects(
        callFunction(seventhToken, "joinRoom", {roomId}),
        (error) => error.code === "RESOURCE_EXHAUSTED",
    );

    await assert.rejects(
        callFunction(firstMemberToken, "clearGroupRoute", {roomId}),
        (error) => error.code === "PERMISSION_DENIED",
    );
    await assert.rejects(
        callFunction(firstMemberToken, "setGroupRoute", {
          roomId,
          origin: {latitude: 6.9271, longitude: 79.8612},
          destination: {latitude: 7.2906, longitude: 80.6337},
        }),
        (error) => error.code === "PERMISSION_DENIED",
    );
    await assert.rejects(
        callFunction(firstMemberToken, "searchDestinations", {
          roomId,
          query: "Colombo",
        }),
        (error) => error.code === "PERMISSION_DENIED",
    );
    await assert.rejects(
        callFunction(ownerToken, "searchDestinations", {roomId, query: "a"}),
        (error) => error.code === "INVALID_ARGUMENT",
    );
    await assert.rejects(
        callFunction(ownerToken, "setGroupRoute", {roomId, origin: null}),
        (error) => error.code === "INVALID_ARGUMENT",
    );
    assert.equal(
        (await callFunction(ownerToken, "clearGroupRoute", {roomId})).cleared,
        true,
    );

    await adminDatabase.ref().update({
      [`room_status/${roomId}/members/${createResult.createdBy}`]: {
        isConnected: false,
        isSpeaking: false,
        micMode: "PTT",
        updatedAt: Date.now(),
      },
      [`navigation_status/${roomId}/members/${createResult.createdBy}`]: {
        active: true,
        updatedAt: Date.now(),
      },
      [`group_trips/${roomId}/locations/${createResult.createdBy}`]: {
        latitude: 6.9271,
        longitude: 79.8612,
      },
    });

    const leaveResult = await callFunction(ownerToken, "leaveRoom", {roomId});
    assert.equal(leaveResult.roomClosed, true);
    assert.equal((await adminDatabase.ref(`room_status/${roomId}`).get()).exists(), false);
    assert.equal(
        (await adminDatabase.ref(`navigation_status/${roomId}`).get()).exists(),
        false,
    );
    assert.equal((await adminDatabase.ref(`group_trips/${roomId}`).get()).exists(), false);

    await assert.rejects(
        callFunction(seventhToken, "joinRoom", {roomId}),
        (error) => error.code === "FAILED_PRECONDITION",
    );
  });
});
