package io.github.susongyan.bobastraw;

/** Redis Cluster hash-slot calculation (CRC16/XMODEM, 0..16383). */
public final class ClusterSlot {
    private ClusterSlot() {
    }

    public static int of(String key) {
        String hashKey = hashTag(key);
        byte[] bytes = hashKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int crc = 0;
        for (byte value : bytes) {
            crc ^= (value & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) == 0 ? crc << 1 : (crc << 1) ^ 0x1021;
                crc &= 0xffff;
            }
        }
        return crc % 16384;
    }

    private static String hashTag(String key) {
        int start = key.indexOf('{');
        if (start < 0) {
            return key;
        }
        int end = key.indexOf('}', start + 1);
        if (end <= start + 1) {
            return key;
        }
        return key.substring(start + 1, end);
    }

    static int ofBytes(byte[] key) {
        int start = 0;
        int end = key.length;
        for (int index = 0; index < key.length; index++) {
            if (key[index] == '{') {
                for (int close = index + 1; close < key.length; close++) {
                    if (key[close] == '}') {
                        if (close > index + 1) {
                            start = index + 1;
                            end = close;
                        }
                        break;
                    }
                }
                break;
            }
        }
        int crc = 0;
        for (int index = start; index < end; index++) {
            crc ^= (key[index] & 0xff) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) == 0 ? crc << 1 : (crc << 1) ^ 0x1021;
                crc &= 0xffff;
            }
        }
        return crc % 16384;
    }
}
