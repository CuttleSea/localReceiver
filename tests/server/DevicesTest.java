import java.nio.file.Files;
import java.nio.file.Path;

import ttdrop.server.Devices;

/**
 * Headless registry test: name validation at pairing (checked before
 * the code is consumed), uniqueness, and rename — including that a
 * rename never touches the device's folder, so devices sharing one
 * browsing root survive it. Run:
 * java -cp dist/ttdrop.jar tests/server/DevicesTest.java
 */
public final class DevicesTest {
    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        Path configDir = Files.createTempDirectory("devices-config");
        Path root = Files.createTempDirectory("devices-root");
        Devices devices = new Devices(configDir);

        String code = devices.newPairingCode();
        check("bad name rejected", "name".equals(devices.pair(code, "Bad-Name", root).error()));
        check("bad name does not burn the code",
            devices.pair(code, "dev_a", root).token() != null);
        check("device folder created", Files.isDirectory(root.resolve("dev_a")));
        String idA = devices.list().get(0).id();

        String code2 = devices.newPairingCode();
        check("duplicate name rejected", "taken".equals(devices.pair(code2, "dev_a", root).error()));
        check("used code rejected", "code".equals(devices.pair(code, "dev_b", root).error()));
        check("fresh code still valid after rejections",
            devices.pair(code2, "dev_b", root).token() != null);

        check("rename rejects upper case", "name".equals(devices.rename(idA, "DevA")));
        check("rename rejects taken name", "taken".equals(devices.rename(idA, "dev_b")));
        check("rename succeeds", devices.rename(idA, "dev_c") == null);
        check("registry carries the new name", devices.get(idA).name().equals("dev_c"));

        // The folder is decoupled from the name: a rename moves nothing
        // and leaves the device pointed at the folder it already had.
        check("device folder not renamed",
            Files.isDirectory(root.resolve("dev_a")) && !Files.exists(root.resolve("dev_c")));
        check("device keeps its original folder",
            devices.get(idA).relPath().equals("dev_a"));

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

        // Legacy pre-v0.18 devices can carry upper-case names/folders;
        // renaming them lower-cases the name only, folder untouched.
        Files.writeString(configDir.resolve("devices.properties"), String.join("\n",
            "d.legacy1.name=Windows",
            "d.legacy1.hash=" + "0".repeat(64),
            "d.legacy1.path=Windows",
            "d.legacy1.read=true", "d.legacy1.write=true", "d.legacy1.browse=true") + "\n");
        Files.createDirectories(root.resolve("Windows"));
        Devices legacy = new Devices(configDir);
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
