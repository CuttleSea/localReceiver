import java.nio.file.Files;
import java.nio.file.Path;

import localreceiver.server.Devices;

/**
 * Headless registry test: name validation at pairing (checked before
 * the code is consumed), uniqueness, and rename — including that a
 * rename never touches the device's folder, so devices sharing one
 * browsing root survive it. Run:
 * java -cp dist/localreceiver.jar tests/server/DevicesTest.java
 */
public final class DevicesTest {
    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        Path configDir = Files.createTempDirectory("devices-config");
        Path root = Files.createTempDirectory("devices-root");
        Path dataDir = Files.createTempDirectory("devices-data");
        Devices devices = new Devices(configDir, dataDir);

        String code = devices.newPairingCode();
        check("bad name rejected", "name".equals(devices.pair(code, "Bad-Name", root).error()));
        check("bad name does not burn the code",
            devices.pair(code, "dev_a", root).token() != null);
        String idA = devices.list().get(0).id();
        check("device folder created under the id", Files.isDirectory(dataDir.resolve(idA)));
        check("working folder starts at the shared folder", devices.get(idA).relPath().isEmpty());
        check("a new device is read-only by default",
            devices.get(idA).read() && !devices.get(idA).write());
        check("nothing named after the device in the shared folder",
            !Files.exists(root.resolve("dev_a")));

        String code2 = devices.newPairingCode();
        check("duplicate name rejected", "taken".equals(devices.pair(code2, "dev_a", root).error()));
        check("bad code with a taken name reports the code, not the name",
            "code".equals(devices.pair("0000-0000", "dev_a", root).error()));
        check("used code rejected", "code".equals(devices.pair(code, "dev_b", root).error()));
        devices.setNewDeviceWrite(true);
        check("fresh code still valid after rejections",
            devices.pair(code2, "dev_b", root).token() != null);
        check("with read+write on, a new device may write", devices.list().stream()
            .filter(d -> d.name().equals("dev_b")).findFirst().orElseThrow().write());
        devices.setNewDeviceWrite(false);

        String code3 = devices.newPairingCode();
        check("taken name rejected (1)", "taken".equals(devices.pair(code3, "dev_a", root).error()));
        check("taken name rejected (2)", "taken".equals(devices.pair(code3, "dev_b", root).error()));
        check("third taken name still reports taken",
            "taken".equals(devices.pair(code3, "dev_a", root).error()));
        check("three rejected names burn the code",
            "code".equals(devices.pair(code3, "dev_new", root).error()));

        check("rename rejects upper case", "name".equals(devices.rename(idA, "DevA")));
        check("rename rejects taken name", "taken".equals(devices.rename(idA, "dev_b")));
        check("rename succeeds", devices.rename(idA, "dev_c") == null);
        check("registry carries the new name", devices.get(idA).name().equals("dev_c"));

        // Both folders are decoupled from the name: a rename moves nothing.
        check("device folder not renamed",
            Files.isDirectory(dataDir.resolve(idA)) && !Files.exists(root.resolve("dev_c")));
        check("device keeps its working folder", devices.get(idA).relPath().isEmpty());

        // Three devices sharing one browsing root: renaming any of them
        // must not move that root nor repoint the others.
        Files.createDirectories(root.resolve("shared"));
        for (String name : new String[] {"shared", "share_b", "share_c"}) {
            devices.update(devices.deviceForToken(
                devices.pair(devices.newPairingCode(), name, root).token()).withRelPath("shared"));
        }
        String idShared = devices.list().stream()
            .filter(d -> d.name().equals("shared")).findFirst().orElseThrow().id();
        check("rename of a device named after the shared folder succeeds",
            devices.rename(idShared, "share_a") == null);
        check("shared folder untouched by the rename",
            Files.isDirectory(root.resolve("shared")) && !Files.exists(root.resolve("share_a")));
        check("all three devices still share the same root",
            devices.list().stream()
                .filter(d -> d.name().startsWith("share_"))
                .filter(d -> d.relPath().equals("shared")).count() == 3);

        // Deny lists match folder names case-insensitively, ignoring
        // trailing dots/spaces and Unicode normalization form.
        Devices.Device denied = new Devices.Device("x", "x", "", true, true, true,
            java.util.Set.of("Private", "Caf\u00e9"), java.util.Set.of("Inbox"));
        check("deny matches exact name", !denied.canReadSub("Private"));
        check("deny matches other case", !denied.canReadSub("PRIVATE"));
        check("deny matches trailing dot", !denied.canReadSub("private."));
        check("deny matches NFD spelling", !denied.canReadSub("Cafe\u0301"));
        check("write deny matches other case", !denied.canWriteSub("inbox"));
        check("other folders stay allowed", denied.canReadSub("Public") && denied.canWriteSub("Private"));

        // Legacy pre-v0.18 devices can carry upper-case names/folders;
        // renaming them lower-cases the name only, folder untouched.
        Files.writeString(configDir.resolve("devices.properties"), String.join("\n",
            "d.legacy1.name=Windows",
            "d.legacy1.hash=" + "0".repeat(64),
            "d.legacy1.path=Windows",
            "d.legacy1.read=true", "d.legacy1.write=true", "d.legacy1.browse=true") + "\n");
        Files.createDirectories(root.resolve("Windows"));
        Devices legacy = new Devices(configDir, dataDir);
        check("legacy uppercase device loads", legacy.get("legacy1") != null);
        check("legacy rename succeeds", legacy.rename("legacy1", "win_pc") == null);
        check("legacy folder stays where it is",
            Files.isDirectory(root.resolve("Windows")) && !Files.exists(root.resolve("win_pc")));
        check("legacy device keeps its folder",
            legacy.get("legacy1").relPath().equals("Windows"));

        System.out.println(fail == 0 ? "TEST PASS" : "TEST FAIL");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void check(String label, boolean cond) {
        if (cond) {
            pass++;
            System.out.println("PASS: " + label);
        } else {
            fail++;
            System.out.println("FAIL: " + label);
        }
    }
}
