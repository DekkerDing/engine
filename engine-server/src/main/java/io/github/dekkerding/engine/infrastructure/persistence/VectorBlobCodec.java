package io.github.dekkerding.engine.infrastructure.persistence;

import io.github.dekkerding.engine.domain.exception.EngineException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 向量 BLOB 编解码器 —— float[] 与 SQLite BLOB 的双向转换。
 *
 * <p>【教学注释 · 为什么是小端（Little-Endian）】
 * 字节序是跨语言数据交换的暗坑：Java 的 ByteBuffer 默认大端，numpy 的 tobytes()
 * 默认小端（&lt;f4）。我们选小端对齐 numpy——将来用 Python 脚本直接读 engine.db
 * 排查数据时 <code>np.frombuffer(blob, dtype='&lt;f4')</code> 零转换命中。
 *
 * <p>【教学注释 · 为什么不用 JSON 存向量】
 * 512 维 float 的 JSON 字符串约 10KB，二进制只有 2KB（float32 定长 4 字节）：
 * 体积 1/5、无解析开销、位级无损。数值密集数据永远用二进制。
 *
 * <p>【精度说明】float32 → BLOB → float32 是位级无损往返（不是序列化"相等"，
 * 是同一个位模式）——单测用 {@link java.util.Arrays#equals} 严格断言。
 */
public final class VectorBlobCodec {

    private VectorBlobCodec() {
    }

    /** float[] → 小端 byte[]（dim × 4 字节） */
    public static byte[] encode(float[] vector) {
        // allocateDirect 不必要：这块内存马上要写进 SQLite，堆内数组拷贝更快
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (float x : vector) {
            buffer.putFloat(x);
        }
        return buffer.array();
    }

    /**
     * 小端 byte[] → float[]。
     *
     * @param expectedDim 期望维度（与 dim 列比对）：BLOB 长度必须是 dim×4，
     *                    否则说明库内数据损坏（比如手改过库），宁可失败也不返回错位向量
     */
    public static float[] decode(byte[] blob, int expectedDim) {
        if (blob == null || blob.length != expectedDim * 4) {
            throw EngineException.internal("向量 BLOB 损坏: 期望 " + expectedDim + " 维("
                    + expectedDim * 4 + " 字节), 实际 " + (blob == null ? -1 : blob.length) + " 字节", null);
        }
        ByteBuffer buffer = ByteBuffer.wrap(blob);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[expectedDim];
        for (int i = 0; i < expectedDim; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
