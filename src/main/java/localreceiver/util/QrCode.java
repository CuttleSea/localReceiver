package localreceiver.util;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal QR code encoder — pure JDK, no dependencies (a hard project
 * constraint), sized for localReceiver's need: encoding short URLs for the GUI.
 *
 * <p>Supports byte mode, versions 1–5, error correction level M (up to
 * 86 payload bytes — a {@code http://<ip>:<port>/} URL is ~30). All
 * eight masks are tried with the standard penalty rules. The output is
 * a boolean module matrix ({@code true} = dark), without quiet zone.
 *
 * <p>Correctness is verified by decoding rendered output with an
 * independent decoder in {@code tests/qr/}.
 */
public final class QrCode {
    private static final int[] TOTAL_CODEWORDS = {0, 26, 44, 70, 100, 134};
    private static final int[] ECC_PER_BLOCK_M = {0, 10, 16, 26, 18, 24};
    private static final int[] NUM_BLOCKS_M = {0, 1, 1, 1, 2, 2};
    private static final int[][] ALIGNMENT = {{}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}};
    private static final int FORMAT_ECC_M = 0; // L=1, M=0, Q=3, H=2

    private final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    /** Encodes text (UTF-8, byte mode) into a QR module matrix. */
    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = -1;
        for (int v = 1; v <= 5; v++) {
            int dataCodewords = TOTAL_CODEWORDS[v] - ECC_PER_BLOCK_M[v] * NUM_BLOCKS_M[v];
            if (4 + 8 + 8 * data.length <= dataCodewords * 8) {
                version = v;
                break;
            }
        }
        if (version < 0) {
            throw new IllegalArgumentException("text too long for QR versions 1-5/M: " + data.length + " bytes");
        }
        QrCode qr = new QrCode(version);
        qr.build(codewords(data, version));
        return qr.modules;
    }

    private QrCode(int version) {
        this.size = version * 4 + 17;
        this.modules = new boolean[size][size];
        this.isFunction = new boolean[size][size];
        drawFunctionPatterns(version);
    }

    /* ---------- data codewords ---------- */

    private static byte[] codewords(byte[] data, int version) {
        int numBlocks = NUM_BLOCKS_M[version];
        int eccLen = ECC_PER_BLOCK_M[version];
        int dataCodewords = TOTAL_CODEWORDS[version] - eccLen * numBlocks;

        // Bit stream: mode 0100, 8-bit count, data, terminator, pad bytes.
        List<Boolean> bits = new ArrayList<>();
        appendBits(bits, 4, 4);
        appendBits(bits, data.length, 8);
        for (byte b : data) {
            appendBits(bits, b & 0xFF, 8);
        }
        int capacity = dataCodewords * 8;
        appendBits(bits, 0, Math.min(4, capacity - bits.size()));
        while (bits.size() % 8 != 0) {
            bits.add(false);
        }
        for (int pad = 0xEC; bits.size() < capacity; pad ^= 0xEC ^ 0x11) {
            appendBits(bits, pad, 8);
        }
        byte[] dataBytes = new byte[dataCodewords];
        for (int i = 0; i < bits.size(); i++) {
            if (bits.get(i)) {
                dataBytes[i / 8] |= (byte) (0x80 >>> (i % 8));
            }
        }

        // Split into blocks (equal-sized for versions 1-5 at level M),
        // compute Reed-Solomon ECC, interleave data then ECC.
        int blockDataLen = dataCodewords / numBlocks;
        byte[][] blocks = new byte[numBlocks][];
        byte[][] eccBlocks = new byte[numBlocks][];
        for (int b = 0; b < numBlocks; b++) {
            byte[] block = new byte[blockDataLen];
            System.arraycopy(dataBytes, b * blockDataLen, block, 0, blockDataLen);
            blocks[b] = block;
            eccBlocks[b] = reedSolomon(block, eccLen);
        }
        byte[] out = new byte[TOTAL_CODEWORDS[version]];
        int k = 0;
        for (int i = 0; i < blockDataLen; i++) {
            for (int b = 0; b < numBlocks; b++) {
                out[k++] = blocks[b][i];
            }
        }
        for (int i = 0; i < eccLen; i++) {
            for (int b = 0; b < numBlocks; b++) {
                out[k++] = eccBlocks[b][i];
            }
        }
        return out;
    }

    private static void appendBits(List<Boolean> bits, int value, int length) {
        for (int i = length - 1; i >= 0; i--) {
            bits.add(((value >>> i) & 1) != 0);
        }
    }

    /* ---------- Reed-Solomon over GF(256), poly 0x11D ---------- */

    private static byte[] reedSolomon(byte[] data, int eccLen) {
        byte[] generator = new byte[eccLen];
        generator[eccLen - 1] = 1;
        int root = 1;
        for (int i = 0; i < eccLen; i++) {
            for (int j = 0; j < eccLen; j++) {
                generator[j] = (byte) gfMul(generator[j] & 0xFF, root);
                if (j + 1 < eccLen) {
                    generator[j] ^= generator[j + 1];
                }
            }
            root = gfMul(root, 2);
        }
        byte[] remainder = new byte[eccLen];
        for (byte b : data) {
            int factor = (b ^ remainder[0]) & 0xFF;
            System.arraycopy(remainder, 1, remainder, 0, eccLen - 1);
            remainder[eccLen - 1] = 0;
            for (int j = 0; j < eccLen; j++) {
                remainder[j] ^= (byte) gfMul(generator[j] & 0xFF, factor);
            }
        }
        return remainder;
    }

    private static int gfMul(int a, int b) {
        int product = 0;
        for (int i = 7; i >= 0; i--) {
            product = (product << 1) ^ ((product >>> 7) * 0x11D);
            product ^= ((b >>> i) & 1) * a;
        }
        return product;
    }

    /* ---------- matrix ---------- */

    private void drawFunctionPatterns(int version) {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        int[] align = ALIGNMENT[version];
        for (int i = 0; i < align.length; i++) {
            for (int j = 0; j < align.length; j++) {
                boolean nearFinder = (i == 0 && j == 0)
                        || (i == 0 && j == align.length - 1)
                        || (i == align.length - 1 && j == 0);
                if (!nearFinder) {
                    drawAlignment(align[i], align[j]);
                }
            }
        }
        // Reserve format info areas so data placement skips them.
        for (int i = 0; i <= 8; i++) {
            reserve(8, i);
            reserve(i, 8);
            if (i < 8) {
                reserve(size - 1 - i, 8);
                reserve(8, size - 1 - i);
            }
        }
    }

    private void drawFinder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) {
                    int dist = Math.max(Math.abs(dx), Math.abs(dy));
                    setFunction(x, y, dist != 2 && dist != 4);
                }
            }
        }
    }

    private void drawAlignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                setFunction(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private void reserve(int x, int y) {
        isFunction[y][x] = true;
    }

    private void build(byte[] codewords) {
        placeCodewords(codewords);
        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(mask);
            drawFormatBits(mask);
            int penalty = penaltyScore();
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestMask = mask;
            }
            applyMask(mask); // XOR is its own inverse
        }
        applyMask(bestMask);
        drawFormatBits(bestMask);
    }

    private void placeCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (isFunction[y][x]) {
                    continue;
                }
                boolean invert = switch (mask) {
                    case 0 -> (x + y) % 2 == 0;
                    case 1 -> y % 2 == 0;
                    case 2 -> x % 3 == 0;
                    case 3 -> (x + y) % 3 == 0;
                    case 4 -> (x / 3 + y / 2) % 2 == 0;
                    case 5 -> x * y % 2 + x * y % 3 == 0;
                    case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                    default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
                };
                modules[y][x] ^= invert;
            }
        }
    }

    private void drawFormatBits(int mask) {
        int data = FORMAT_ECC_M << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) {
            setFunction(8, i, bit(bits, i));
        }
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) {
            setFunction(14 - i, 8, bit(bits, i));
        }
        for (int i = 0; i <= 7; i++) {
            setFunction(size - 1 - i, 8, bit(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            setFunction(8, size - 15 + i, bit(bits, i));
        }
        setFunction(8, size - 8, true);
    }

    private static boolean bit(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    /* Standard penalty rules; affects only mask choice — any mask is valid. */
    private int penaltyScore() {
        int penalty = 0;
        // Rule 1: runs of 5+ same-colored modules, rows and columns.
        for (int axis = 0; axis < 2; axis++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean current = axis == 0 ? modules[a][b] : modules[b][a];
                    boolean previous = axis == 0 ? modules[a][b - 1] : modules[b - 1][a];
                    if (current == previous) {
                        run++;
                        if (run == 5) {
                            penalty += 3;
                        } else if (run > 5) {
                            penalty++;
                        }
                    } else {
                        run = 1;
                    }
                }
            }
        }
        // Rule 2: 2x2 blocks of same color.
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) {
                    penalty += 3;
                }
            }
        }
        // Rule 3: finder-like 1:1:3:1:1 pattern with 4 light modules on a side.
        int[] pattern = {1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0};
        for (int axis = 0; axis < 2; axis++) {
            for (int a = 0; a < size; a++) {
                for (int b = 0; b + pattern.length <= size; b++) {
                    boolean forward = true;
                    boolean backward = true;
                    for (int k = 0; k < pattern.length; k++) {
                        boolean m = axis == 0 ? modules[a][b + k] : modules[b + k][a];
                        if (m != (pattern[k] == 1)) {
                            forward = false;
                        }
                        if (m != (pattern[pattern.length - 1 - k] == 1)) {
                            backward = false;
                        }
                    }
                    if (forward || backward) {
                        penalty += 40;
                    }
                }
            }
        }
        // Rule 4: dark-module proportion deviation from 50%.
        int dark = 0;
        for (boolean[] row : modules) {
            for (boolean m : row) {
                if (m) {
                    dark++;
                }
            }
        }
        int total = size * size;
        penalty += 10 * ((Math.abs(dark * 20 - total * 10) + total - 1) / total);
        return penalty;
    }

    /* ---------- rendering ---------- */

    /** Renders a matrix to an image, {@code scale} px per module with a 4-module quiet zone. */
    public static BufferedImage toImage(boolean[][] matrix, int scale) {
        int quiet = 4;
        int px = (matrix.length + 2 * quiet) * scale;
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                int mx = x / scale - quiet;
                int my = y / scale - quiet;
                boolean dark = mx >= 0 && my >= 0 && mx < matrix.length && my < matrix.length && matrix[my][mx];
                img.setRGB(x, y, dark ? 0x000000 : 0xFFFFFF);
            }
        }
        return img;
    }

    /** Test utility: {@code java -cp localreceiver.jar localreceiver.util.QrCode <text> <out.png>} */
    public static void main(String[] args) throws Exception {
        javax.imageio.ImageIO.write(toImage(encode(args[0]), 8), "png", Path.of(args[1]).toFile());
    }
}
