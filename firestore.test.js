const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_host_123";
const BOB_UID = "bob_ctrl_456";
const EVE_UID = "eve_intruder_789";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function pastDate(msOffset = 1000) {
  return new Date(Date.now() - msOffset);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write devices, pairings, or sessions", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("devices").get());
  await assertFails(unauthDb.collection("pairings").doc("482910").get());
  await assertFails(unauthDb.collection("sessions").get());
  await assertFails(
    unauthDb.collection("devices").doc("dev_1").set({
      ownerUid: ALICE_UID,
      deviceName: "Pixel 8",
      pairingCode: "482910",
      roleMode: "DUAL",
      alwaysAllowControl: true,
      screenShareActive: false,
      accessibilityActive: false,
      trustedUids: [ALICE_UID],
      screenWidth: 1080,
      screenHeight: 2400,
      lastSeenAt: pastDate(),
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});

test("Device CRUD: owner can create, update, query; non-trusted user denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const eveDb = testEnv.authenticatedContext(EVE_UID).firestore();
  const t = pastDate(5000);

  // Valid Kotlin-aligned create payload (no id field)
  await assertSucceeds(
    aliceDb.collection("devices").doc("dev_alice").set({
      ownerUid: ALICE_UID,
      deviceName: "Alice Phone 2",
      pairingCode: "482910",
      roleMode: "HOST",
      alwaysAllowControl: true,
      screenShareActive: true,
      accessibilityActive: true,
      trustedUids: [ALICE_UID],
      screenWidth: 1080,
      screenHeight: 2400,
      lastSeenAt: t,
      createdAt: t,
      updatedAt: t,
    })
  );

  // Filtered query matches trustedUids
  await assertSucceeds(
    aliceDb.collection("devices").where("trustedUids", "array-contains", ALICE_UID).get()
  );

  // Unfiltered list query denied
  await assertFails(aliceDb.collection("devices").get());

  // Partial update preserving createdAt succeeds
  await assertSucceeds(
    aliceDb.collection("devices").doc("dev_alice").update({
      trustedUids: [ALICE_UID, BOB_UID],
      updatedAt: pastDate(500),
    })
  );

  // Shadow field or immutable createdAt mutation fails
  await assertFails(
    aliceDb.collection("devices").doc("dev_alice").update({
      createdAt: pastDate(100),
      updatedAt: pastDate(100),
    })
  );
  await assertFails(
    aliceDb.collection("devices").doc("dev_alice").update({
      ghostField: true,
      updatedAt: pastDate(100),
    })
  );

  // Eve (not in trustedUids) cannot read or update Alice's device
  await assertFails(eveDb.collection("devices").doc("dev_alice").get());
  await assertFails(
    eveDb.collection("devices").doc("dev_alice").update({
      alwaysAllowControl: false,
      updatedAt: pastDate(100),
    })
  );

  // Bob (in trustedUids) can read Alice's device, but cannot overwrite Alice's device settings
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertSucceeds(bobDb.collection("devices").doc("dev_alice").get());
  await assertFails(
    bobDb.collection("devices").doc("dev_alice").update({
      alwaysAllowControl: false,
      updatedAt: pastDate(100),
    })
  );
});

test("Pairing code flow: host opens 6-digit code, controller claims, no listing allowed by non-host", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const t = pastDate(5000);

  await assertSucceeds(
    aliceDb.collection("pairings").doc("482910").set({
      hostUid: ALICE_UID,
      hostDeviceId: "dev_alice",
      hostDeviceName: "Alice Phone 2",
      controllerUid: "",
      controllerDeviceName: "",
      status: "OPEN",
      alwaysAllow: true,
      createdAt: t,
      updatedAt: t,
    })
  );

  // Non-host cannot list pairings to scrape codes
  await assertFails(bobDb.collection("pairings").get());

  // Bob looks up exact 6-digit code and claims it
  await assertSucceeds(bobDb.collection("pairings").doc("482910").get());
  await assertSucceeds(
    bobDb.collection("pairings").doc("482910").update({
      status: "CLAIMED",
      controllerUid: BOB_UID,
      controllerDeviceName: "Bob Phone 1",
      updatedAt: pastDate(500),
    })
  );

  // Second claim attempt by Eve fails because status is no longer OPEN
  const eveDb = testEnv.authenticatedContext(EVE_UID).firestore();
  await assertFails(
    eveDb.collection("pairings").doc("482910").update({
      status: "CLAIMED",
      controllerUid: EVE_UID,
      controllerDeviceName: "Eve Phone",
      updatedAt: pastDate(200),
    })
  );
});

test("Session, remote commands, and shared photos enforce trusted device & participant isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const eveDb = testEnv.authenticatedContext(EVE_UID).firestore();
  const t = pastDate(5000);

  // Register Alice's host device with Bob trusted
  await assertSucceeds(
    aliceDb.collection("devices").doc("dev_alice").set({
      ownerUid: ALICE_UID,
      deviceName: "Alice Phone 2",
      pairingCode: "482910",
      roleMode: "HOST",
      alwaysAllowControl: true,
      screenShareActive: true,
      accessibilityActive: true,
      trustedUids: [ALICE_UID, BOB_UID],
      screenWidth: 1080,
      screenHeight: 2400,
      lastSeenAt: t,
      createdAt: t,
      updatedAt: t,
    })
  );

  // Eve cannot create a session targeting Alice's device (not in trustedUids)
  await assertFails(
    eveDb.collection("sessions").doc("sess_bad").set({
      hostUid: ALICE_UID,
      hostDeviceId: "dev_alice",
      hostDeviceName: "Alice Phone 2",
      controllerUid: EVE_UID,
      controllerDeviceId: "dev_eve",
      controllerDeviceName: "Eve Phone",
      participantUids: [ALICE_UID, EVE_UID],
      status: "ACTIVE",
      alwaysAllow: true,
      remoteControlEnabled: true,
      latestFrameBase64: "",
      frameSeq: 0,
      frameWidth: 720,
      frameHeight: 1280,
      createdAt: t,
      updatedAt: t,
    })
  );

  // Trusted Bob creates a session targeting Alice's device
  await assertSucceeds(
    bobDb.collection("sessions").doc("sess_1").set({
      hostUid: ALICE_UID,
      hostDeviceId: "dev_alice",
      hostDeviceName: "Alice Phone 2",
      controllerUid: BOB_UID,
      controllerDeviceId: "dev_bob",
      controllerDeviceName: "Bob Phone 1",
      participantUids: [ALICE_UID, BOB_UID],
      status: "ACTIVE",
      alwaysAllow: true,
      remoteControlEnabled: true,
      latestFrameBase64: "",
      frameSeq: 0,
      frameWidth: 720,
      frameHeight: 1280,
      createdAt: t,
      updatedAt: t,
    })
  );

  // Host Alice streams frame updates
  await assertSucceeds(
    aliceDb.collection("sessions").doc("sess_1").update({
      latestFrameBase64: "ZmFrZWZyYW1l",
      frameSeq: 1,
      frameWidth: 540,
      frameHeight: 1200,
      updatedAt: pastDate(500),
    })
  );

  // Filtered session query succeeds; unfiltered fails
  await assertSucceeds(
    bobDb.collection("sessions").where("participantUids", "array-contains", BOB_UID).get()
  );
  await assertFails(bobDb.collection("sessions").get());

  // Bob dispatches a remote TAP command
  await assertSucceeds(
    bobDb.collection("sessions").doc("sess_1").collection("commands").doc("cmd_1").set({
      senderUid: BOB_UID,
      hostUid: ALICE_UID,
      participantUids: [ALICE_UID, BOB_UID],
      type: "TAP",
      normX: 0.5,
      normY: 0.5,
      endNormX: 0.5,
      endNormY: 0.5,
      status: "PENDING",
      createdAt: t,
      updatedAt: t,
    })
  );

  // Host Alice marks command EXECUTED
  await assertSucceeds(
    aliceDb.collection("sessions").doc("sess_1").collection("commands").doc("cmd_1").update({
      status: "EXECUTED",
      updatedAt: pastDate(200),
    })
  );

  // Eve cannot read, list unfiltered, or query Alice's commands, or inject commands
  await assertFails(
    eveDb.collection("sessions").doc("sess_1").collection("commands").get()
  );
  await assertFails(
    eveDb.collection("sessions").doc("sess_1").collection("commands").where("participantUids", "array-contains", ALICE_UID).get()
  );
  await assertFails(
    eveDb.collection("sessions").doc("sess_1").collection("commands").doc("cmd_1").get()
  );
  await assertFails(
    eveDb.collection("sessions").doc("sess_1").collection("commands").doc("cmd_eve").set({
      senderUid: EVE_UID,
      hostUid: ALICE_UID,
      participantUids: [ALICE_UID, EVE_UID],
      type: "HOME",
      normX: 0.5,
      normY: 0.5,
      endNormX: 0.5,
      endNormY: 0.5,
      status: "PENDING",
      createdAt: t,
      updatedAt: t,
    })
  );

  // Bob shares a gallery photo into the session
  await assertSucceeds(
    bobDb.collection("sessions").doc("sess_1").collection("sharedPhotos").doc("photo_1").set({
      senderUid: BOB_UID,
      senderDeviceName: "Bob Phone 1",
      participantUids: [ALICE_UID, BOB_UID],
      title: "Shared Photo",
      source: "GALLERY_PHOTO",
      imageBase64: "ZmFrZXBob3RvZGF0YQ==",
      createdAt: t,
      updatedAt: t,
    })
  );

  // Filtered photo query succeeds for Alice; Eve cannot access
  await assertSucceeds(
    aliceDb.collection("sessions").doc("sess_1").collection("sharedPhotos").where("participantUids", "array-contains", ALICE_UID).get()
  );
  await assertFails(
    eveDb.collection("sessions").doc("sess_1").collection("sharedPhotos").doc("photo_1").get()
  );
});
