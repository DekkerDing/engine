package io.github.dekkerding.engine.infrastructure.persistence;

import io.github.dekkerding.engine.domain.exception.EngineException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * BLOB 编解码单测 —— 任务 2.1 的验收点：float[] → BLOB → float[] 位级无损。
 *
 * <p>【教学注释 · 位级 vs 数值级】位级（Arrays.equals）比"每项差 < 1e-6"严格：
 * 证明编解码没做任何舍入，浮点数只是换了身衣服（内存 → 字节 → 内存）。
 */
class VectorBlobCodecTest {

    @Test
    void 往返位级无损_普通值() {
        float[] vector = {0.123f, -0.456f, 0.789f, -1.0f, 0.0f, 1.0f};
        assertArrayEquals(vector, VectorBlobCodec.decode(VectorBlobCodec.encode(vector), vector.length));
    }

    @Test
    void 往返位级无损_极端浮点值() {
        // 极小值（下溢边缘）、极大值、负零：任何字节序/精度处理不当都会在这里现形
        float[] vector = {1.2345678E-5f, -0.98765432f, Float.MAX_VALUE, -Float.MIN_VALUE, -0.0f};
        assertArrayEquals(vector, VectorBlobCodec.decode(VectorBlobCodec.encode(vector), vector.length));
    }

    @Test
    void 字节数是维度乘四() {
        byte[] blob = VectorBlobCodec.encode(new float[512]);
        assertEquals(512 * 4, blob.length);
    }

    @Test
    void 空向量往返() {
        assertArrayEquals(new float[0], VectorBlobCodec.decode(VectorBlobCodec.encode(new float[0]), 0));
    }

    @Test
    void blob长度与维度不符_抛明确错误() {
        byte[] blob = VectorBlobCodec.encode(new float[4]); // 16 字节
        EngineException e = assertThrows(EngineException.class,
                () -> VectorBlobCodec.decode(blob, 5)); // 5 维应为 20 字节
        assertEquals(500, e.getCode());
    }

    @Test
    void nullBlob_抛明确错误() {
        assertThrows(EngineException.class, () -> VectorBlobCodec.decode(null, 4));
    }

    @Test
    void 编码是小端_与numpy对齐() {
        // 1.0f 的 IEEE754 位模式是 0x3F800000。
        //   大端（Java ByteBuffer 默认）: 3F 80 00 00 —— 高位字节在前
        //   小端（numpy '<f4' 默认）    : 00 00 80 3F —— 低位字节在前
        // 断言首字节 0x00 / 末字节 0x3F：锁死小端约定，谁改成大端谁就被测试抓住
        byte[] blob = VectorBlobCodec.encode(new float[]{1.0f});
        assertEquals(0x00, blob[0] & 0xFF);
        assertEquals(0x00, blob[1] & 0xFF);
        assertEquals((byte) 0x80, blob[2]);
        assertEquals(0x3F, blob[3] & 0xFF);
    }
}
