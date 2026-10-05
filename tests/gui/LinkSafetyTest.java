import localreceiver.gui.ServerWindow;

/**
 * Headless check that a live pairing code never reaches a subprocess
 * command line: every URL the window hands to the browser goes through
 * ServerWindow.stripPairCode first, and argv is readable by any other
 * local user. Run:
 * java -Djava.awt.headless=true -cp dist/localreceiver.jar tests/gui/LinkSafetyTest.java
 */
public final class LinkSafetyTest {
    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) {
        check("pair code dropped",
            "https://192.168.1.5:4646/".equals(
                ServerWindow.stripPairCode("https://192.168.1.5:4646/?pair=ABCD-2345")));
        check("plain app URL untouched",
            "https://192.168.1.5:4646/".equals(
                ServerWindow.stripPairCode("https://192.168.1.5:4646/")));
        check("files URL untouched",
            "http://host:4646/files/".equals(
                ServerWindow.stripPairCode("http://host:4646/files/")));
        check("other parameters survive",
            "http://host:4646/?a=1&b=2".equals(
                ServerWindow.stripPairCode("http://host:4646/?a=1&pair=ABCD-2345&b=2")));
        check("pair first among several",
            "http://host:4646/?a=1".equals(
                ServerWindow.stripPairCode("http://host:4646/?pair=ABCD-2345&a=1")));
        check("valueless pair dropped",
            "http://host:4646/".equals(ServerWindow.stripPairCode("http://host:4646/?pair")));
        check("percent-encoded code dropped",
            "http://host:4646/".equals(
                ServerWindow.stripPairCode("http://host:4646/?pair=ABCD%2D2345")));
        check("a parameter merely starting with pair is kept",
            "http://host:4646/?paired=yes".equals(
                ServerWindow.stripPairCode("http://host:4646/?paired=yes")));
        check("empty query left as a bare path",
            "http://host:4646/".equals(ServerWindow.stripPairCode("http://host:4646/?")));
        // The guarantee that matters: no output may carry the code.
        String[] inputs = {
            "https://h:1/?pair=ABCD-2345",
            "https://h:1/?a=1&pair=ABCD-2345",
            "https://h:1/?pair=ABCD-2345&a=1",
        };
        boolean leaked = false;
        for (String input : inputs) {
            if (ServerWindow.stripPairCode(input).contains("ABCD-2345")) {
                leaked = true;
            }
        }
        check("no stripped URL still carries the code", !leaked);

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
