const fs = require("node:fs");
const path = require("node:path");
const {after, before, beforeEach, describe, it} = require("node:test");
const {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} = require("@firebase/rules-unit-testing");
const {
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  setDoc,
} = require("firebase/firestore");
const {
  get,
  ref,
  remove,
  set,
} = require("firebase/database");

const PROJECT_ID = "demo-travel-buddy";
const ROOM_ID = "123456";
const REPO_ROOT = path.resolve(__dirname, "..", "..");
let testEnvironment;

function roomFirestore(uid) {
  return testEnvironment.authenticatedContext(uid).firestore();
}

function roomDatabase(uid) {
  return testEnvironment.authenticatedContext(uid).database();
}

before(async () => {
  testEnvironment = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: fs.readFileSync(path.join(REPO_ROOT, "firestore.rules"), "utf8"),
    },
    database: {
      rules: fs.readFileSync(path.join(REPO_ROOT, "database.rules.json"), "utf8"),
    },
  });
});

beforeEach(async () => {
  await testEnvironment.clearFirestore();
  await testEnvironment.clearDatabase();
  await testEnvironment.withSecurityRulesDisabled(async (context) => {
    const firestore = context.firestore();
    await setDoc(doc(firestore, `rooms/${ROOM_ID}`), {
      roomId: ROOM_ID,
      roomCode: ROOM_ID,
      createdBy: "owner",
      status: "ACTIVE",
      memberCount: 2,
    });
    await setDoc(doc(firestore, `rooms/${ROOM_ID}/members/owner`), {
      uid: "owner",
    });
    await setDoc(doc(firestore, `rooms/${ROOM_ID}/members/member`), {
      uid: "member",
    });

    await set(ref(context.database()), {
      room_access: {
        [ROOM_ID]: {
          ownerUid: "owner",
          members: {owner: true, member: true},
        },
      },
      room_status: {
        [ROOM_ID]: {
          members: {
            member: {
              isConnected: true,
              isSpeaking: false,
              micMode: "PTT",
              updatedAt: 1,
            },
          },
        },
      },
      navigation_status: {
        [ROOM_ID]: {
          members: {
            member: {
              active: true,
              updatedAt: 1,
            },
          },
        },
      },
      signaling: {
        [ROOM_ID]: {
          owner_member: {offer: {fromUid: "owner"}},
        },
      },
      group_trips: {
        [ROOM_ID]: {
          route: {
            distanceMeters: 1000,
            durationSeconds: 120,
          },
        },
      },
    });
  });
});

after(async () => {
  await testEnvironment.cleanup();
});

describe("Firestore room rules", () => {
  it("lets members read their room and member list", async () => {
    const firestore = roomFirestore("member");
    await assertSucceeds(getDoc(doc(firestore, `rooms/${ROOM_ID}`)));
    await assertSucceeds(getDocs(collection(
        firestore,
        `rooms/${ROOM_ID}/members`,
    )));
  });

  it("denies room reads to signed-in outsiders", async () => {
    const firestore = roomFirestore("outsider");
    await assertFails(getDoc(doc(firestore, `rooms/${ROOM_ID}`)));
    await assertFails(getDocs(collection(
        firestore,
        `rooms/${ROOM_ID}/members`,
    )));
  });

  it("denies direct room and membership mutations", async () => {
    const firestore = roomFirestore("member");
    await assertFails(setDoc(
        doc(firestore, `rooms/${ROOM_ID}/members/outsider`),
        {uid: "outsider"},
    ));
    await assertFails(deleteDoc(doc(firestore, `rooms/${ROOM_ID}`)));
  });
});

describe("Realtime Database room rules", () => {
  it("lets members read and write room signaling", async () => {
    const database = roomDatabase("member");
    await assertSucceeds(get(ref(database, `signaling/${ROOM_ID}`)));
    await assertSucceeds(set(
        ref(database, `signaling/${ROOM_ID}/owner_member/answer`),
        {fromUid: "member"},
    ));
  });

  it("requires active voice participation before signaling", async () => {
    const database = roomDatabase("owner");
    await assertFails(get(ref(database, `signaling/${ROOM_ID}`)));
    await assertFails(set(
        ref(database, `signaling/${ROOM_ID}/owner_member/offer`),
        {fromUid: "owner"},
    ));
    await assertSucceeds(set(
        ref(database, `room_status/${ROOM_ID}/members/owner`),
        {
          isConnected: false,
          isSpeaking: false,
          micMode: "PTT",
          updatedAt: 2,
        },
    ));
    await assertSucceeds(get(ref(database, `signaling/${ROOM_ID}`)));
  });

  it("denies signaling and access data to outsiders", async () => {
    const database = roomDatabase("outsider");
    await assertFails(get(ref(database, `signaling/${ROOM_ID}`)));
    await assertFails(get(ref(database, `room_access/${ROOM_ID}`)));
    await assertFails(set(
        ref(database, `signaling/${ROOM_ID}/fake/offer`),
        {fromUid: "outsider"},
    ));
  });

  it("lets a member update only their valid live status", async () => {
    const database = roomDatabase("member");
    const statusRef = ref(database, `room_status/${ROOM_ID}/members/member`);
    const status = {
      isConnected: true,
      isSpeaking: true,
      micMode: "TOGGLE",
      updatedAt: 2,
    };
    await assertSucceeds(remove(statusRef));
    await assertFails(set(statusRef, {
      isConnected: false,
      isSpeaking: false,
      updatedAt: 2,
    }));
    await assertSucceeds(set(statusRef, status));
    await assertFails(set(
        ref(database, `room_status/${ROOM_ID}/members/owner`),
        status,
    ));
    await assertFails(set(
        statusRef,
        {...status, unexpected: true},
    ));
    await assertSucceeds(remove(statusRef));
  });

  it("denies client writes to the membership mirror", async () => {
    const database = roomDatabase("owner");
    await assertFails(set(
        ref(database, `room_access/${ROOM_ID}/members/outsider`),
        true,
    ));
  });

  it("shares the server route with members as read-only data", async () => {
    const memberDatabase = roomDatabase("member");
    const outsiderDatabase = roomDatabase("outsider");
    await assertSucceeds(get(ref(
        memberDatabase,
        `group_trips/${ROOM_ID}/route`,
    )));
    await assertFails(get(ref(
        outsiderDatabase,
        `group_trips/${ROOM_ID}/route`,
    )));
    await assertFails(set(
        ref(memberDatabase, `group_trips/${ROOM_ID}/route/distanceMeters`),
        5,
    ));
  });

  it("lets members publish only their own valid live location", async () => {
    const memberDatabase = roomDatabase("member");
    const outsiderDatabase = roomDatabase("outsider");
    const location = {
      latitude: 6.9271,
      longitude: 79.8612,
      accuracyMeters: 8,
      speedMetersPerSecond: 12.5,
      hasSpeed: true,
      bearingDegrees: 180,
      updatedAt: Date.now(),
    };
    const ownLocation = ref(
        memberDatabase,
        `group_trips/${ROOM_ID}/locations/member`,
    );
    await assertSucceeds(set(ownLocation, location));
    await assertFails(set(
        ref(memberDatabase, `group_trips/${ROOM_ID}/locations/owner`),
        location,
    ));
    await assertFails(set(
        ref(outsiderDatabase, `group_trips/${ROOM_ID}/locations/outsider`),
        location,
    ));
    await assertFails(set(ownLocation, {...location, latitude: 91}));
    await assertFails(set(ownLocation, {...location, unexpected: true}));
    await assertSucceeds(remove(ownLocation));
  });

  it("requires navigation participation before publishing location", async () => {
    const database = roomDatabase("owner");
    const statusRef = ref(
        database,
        `navigation_status/${ROOM_ID}/members/owner`,
    );
    const locationRef = ref(database, `group_trips/${ROOM_ID}/locations/owner`);
    const location = {
      latitude: 6.9271,
      longitude: 79.8612,
      accuracyMeters: 8,
      speedMetersPerSecond: 0,
      hasSpeed: false,
      bearingDegrees: 0,
      updatedAt: Date.now(),
    };
    await assertFails(set(locationRef, location));
    await assertSucceeds(set(statusRef, {active: true, updatedAt: Date.now()}));
    await assertSucceeds(set(locationRef, location));
    await assertFails(set(
        ref(database, `navigation_status/${ROOM_ID}/members/member`),
        {active: true, updatedAt: Date.now()},
    ));
    await assertSucceeds(remove(statusRef));
    await assertSucceeds(remove(locationRef));
  });
});

