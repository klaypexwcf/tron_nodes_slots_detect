package MyConnection;

import com.google.protobuf.ByteString;
import org.apache.commons.lang3.StringUtils;
import org.tron.p2p.discover.Node;
import org.tron.p2p.protos.Discover;
import org.tron.p2p.utils.ByteArray;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.Base64;

public class MyUtil {
    public static final String DATA_LEN=", len=";

    public static boolean[] byteArrayToBitArray(Byte[] byteArray) {
        int byteLength = byteArray.length;
        boolean[] bitArray = new boolean[byteLength * 8];

        for (int i = 0; i < byteLength; i++) {
            for (int bit = 0; bit < 8; bit++) {
                // 将每个字节的每一位提取出来，并存储在位数组中
                bitArray[i * 8 + bit] = (byteArray[i] & (1 << (7 - bit))) != 0;
            }
        }

        return bitArray;
    }
    public static byte[] toPrimitive(Byte[] byteObjects) {
        if (byteObjects == null) {
            return null;
        }
        byte[] bytes = new byte[byteObjects.length];
        for (int i = 0; i < byteObjects.length; i++) {
            // 检查是否为 null，避免空指针异常
            bytes[i] = byteObjects[i] != null ? byteObjects[i] : 0;
        }
        return bytes;
    }

    public static void copyFirstIBits(boolean[] source, boolean[] destination, int i) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("源数组和目标数组不能为空。");
        }
        if (i < 0) {
            throw new IllegalArgumentException("要复制的位数 i 不能为负。");
        }
        if (source.length < i) {
            throw new IllegalArgumentException("源数组的长度不足以复制 " + i + " 位。");
        }
        if (destination.length < i) {
            throw new IllegalArgumentException("目标数组的长度不足以复制 " + i + " 位。");
        }

        System.arraycopy(source, 0, destination, 0, i);
    }
    public static Byte[] bitArrayToByteArray(boolean[] bitArray) {
        if (bitArray.length % 8 != 0) {
            throw new IllegalArgumentException("位数组的长度必须是8的倍数。");
        }

        int byteLength = bitArray.length / 8;
        Byte[] byteArray = new Byte[byteLength];

        for (int i = 0; i < byteLength; i++) {
            byte b = 0;
            for (int bit = 0; bit < 8; bit++) {
                if (bitArray[i * 8 + bit]) {
                    b |= (1 << (7 - bit));
                }
            }
            byteArray[i] = b;
        }

        return byteArray;
    }
    public static byte[] decodeFromBase64(String base64Str) {
        return Base64.getDecoder().decode(base64Str);
    }
    public static String encodeToBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
    public static Byte[] toByteArray(byte[] byteArray) {
        // 创建一个新的 Byte[] 数组，长度与 byteArray 相同
        Byte[] byteObjectArray = new Byte[byteArray.length];

        // 将 byte[] 中的每个元素转换为 Byte 对象，并存储在 Byte[] 中
        for (int i = 0; i < byteArray.length; i++) {
            byteObjectArray[i] = byteArray[i]; // 自动装箱
        }

        return byteObjectArray;
    }

    public static ByteString base64ToByteString(String base64String) {
        // Step 1: 去除前导 0
        // 假设前导0是字符 '0'
        base64String = base64String.replaceFirst("^0+", "");

        // Step 2: Base64 解码
        byte[] decodedBytes = Base64.getDecoder().decode(base64String);

        // Step 3: 创建 ByteString 对象
        return ByteString.copyFrom(decodedBytes);
    }
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }
    public static byte[] generateRandomNodeId() {
        // 1. 创建一个 64 字节的随机字节数组
        byte[] randomBytes = new byte[64];
        SecureRandom random = new SecureRandom();
        random.nextBytes(randomBytes);  // 生成随机字节

        return randomBytes;
    }
    public static String byteArrayToHexString(byte[] byteArray) {
        if (byteArray == null || byteArray.length == 0) {
            return "";
        }

        StringBuilder hexStringBuilder = new StringBuilder();
        for (byte b : byteArray) {
            // 格式化为两位十六进制
            hexStringBuilder.append(String.format("%02X", b));
        }
        return hexStringBuilder.toString();
    }
    public static Discover.Endpoint getEndpointFromNode(Node node) {
        Discover.Endpoint.Builder builder = Discover.Endpoint.newBuilder()
                .setPort(node.getPort());
        if (node.getId() != null) {
            builder.setNodeId(ByteString.copyFrom(node.getId()));
        }
        if (StringUtils.isNotEmpty(node.getHostV4())) {
            builder.setAddress(ByteString.copyFrom(ByteArray.fromString(node.getHostV4())));
        }
        if (StringUtils.isNotEmpty(node.getHostV6())) {
            builder.setAddressIpv6(ByteString.copyFrom(ByteArray.fromString(node.getHostV6())));
        }
        return builder.build();
    }
    public static Node wrapNode(String localIP,int localPort,byte[] localId){
        return new Node(localId,localIP,"",localPort);
    }
    public static int getAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { // 0 表示让系统分配一个空闲端口
            return socket.getLocalPort();
        }
    }
}

