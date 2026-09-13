package io.github.dekkerding.engine.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dekkerding.engine.application.RequirementApplicationService;
import io.github.dekkerding.engine.domain.model.requirement.RequirementForm;
import io.github.dekkerding.engine.domain.service.RequirementTestFixtures;
import io.github.dekkerding.engine.infrastructure.persistence.DatabaseMigrator;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteAttachmentRepository;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteConnectionManager;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteMdArtifactRepository;
import io.github.dekkerding.engine.infrastructure.persistence.SqliteRequirementRepository;
import io.github.dekkerding.engine.infrastructure.requirement.AttachmentFormatGuard;
import io.github.dekkerding.engine.infrastructure.requirement.LocalFileAttachmentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 需求工厂 REST 测试 —— 任务 3.3 验收：关键路径
 * 创建 → 422 闸门 → 补齐提交 → 双目标渲染 → 修订 → 导出（zip/md）→ 附件。
 * standalone MockMvc + 真实应用服务（@TempDir SQLite）+ 真实全局异常翻译。
 */
class RequirementControllerTest {

    @TempDir
    Path tempDir;

    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        SqliteConnectionManager connectionManager =
                new SqliteConnectionManager(tempDir.resolve("engine.db").toString());
        new DatabaseMigrator(connectionManager, "text-embedding-zh").migrate();
        RequirementApplicationService service = new RequirementApplicationService(
                new SqliteRequirementRepository(connectionManager),
                new SqliteMdArtifactRepository(connectionManager),
                new SqliteAttachmentRepository(connectionManager),
                new LocalFileAttachmentStore(tempDir.resolve("requirements").toString()),
                new AttachmentFormatGuard());
        mockMvc = MockMvcBuilders.standaloneSetup(new RequirementController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 关键路径_创建_闸门422_补齐_渲染_修订_导出md() throws Exception {
        // 1. 创建（部分字段：第一步暂存场景）
        RequirementForm partial = new RequirementForm();
        partial.getBasic().setTitle("订单导出");
        String id = create(partial);
        assertTrue(id.length() == 36, "UUID 长度: " + id);

        // 2. 空 body 防御
        mockMvc.perform(post("/requirements")).andExpect(status().isBadRequest());

        // 3. 提交缺验收 → 422 + 缺失清单 + 信封 code=1000
        MvcResult gate = mockMvc.perform(post("/requirements/{id}/submit", id))
                .andExpect(status().is(422)).andReturn();
        JsonNode gateBody = json.readTree(utf8Body(gate));
        assertEquals(1000, gateBody.get("code").intValue());
        assertTrue(gateBody.get("message").asText().contains("验收标准至少一条"));

        // 4. 全量表单保存（PUT）→ 补齐后提交 → SUBMITTED
        RequirementForm full = RequirementTestFixtures.fullForm();
        mockMvc.perform(put("/requirements/{id}", id)
                        .contentType("application/json").content(json.writeValueAsString(full)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/requirements/{id}/submit", id))
                .andExpect(status().isOk());

        // 5. 双目标渲染
        MvcResult rendered = mockMvc.perform(post("/requirements/{id}/render", id)
                        .contentType("application/json").content("{\"target\":\"openspec\"}"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(1, json.readTree(utf8Body(rendered))
                .get("data").get("version").intValue());
        mockMvc.perform(post("/requirements/{id}/render", id)
                        .contentType("application/json").content("{\"target\":\"vibecoding\"}"))
                .andExpect(status().isOk());

        // 6. 无效 target → 400
        mockMvc.perform(post("/requirements/{id}/render", id)
                        .contentType("application/json").content("{\"target\":\"word\"}"))
                .andExpect(status().isBadRequest());

        // 7. 读取工件内容（含模板基准）
        MvcResult content = mockMvc.perform(get("/requirements/{id}/artifacts/{version}", id, 1)
                        .param("target", "vibecoding"))
                .andExpect(status().isOk()).andReturn();
        assertTrue(json.readTree(utf8Body(content))
                .get("data").get("files").get("TASK.md").asText().contains("订单导出"));

        // 8. 保存修订 → 导出 md（内容 = 修订版）
        Map<String, String> revised = new LinkedHashMap<String, String>();
        revised.put("TASK.md", "# 任务：修订版\n");
        mockMvc.perform(put("/requirements/{id}/artifacts/{version}", id, 1)
                        .contentType("application/json")
                        .content("{\"target\":\"vibecoding\",\"files\":{\"TASK.md\":\"# 任务：修订版\\n\"}}"))
                .andExpect(status().isOk());
        MvcResult exported = mockMvc.perform(get("/requirements/{id}/export", id)
                        .param("target", "vibecoding"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("# 任务：修订版\n", exported.getResponse().getContentAsString());
        assertEquals("false", exported.getResponse().getHeader("X-Artifact-Stale"));

        // 9. 列表过滤
        MvcResult list = mockMvc.perform(get("/requirements")
                        .param("status", "EXPORTED").param("q", "订单"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(1, json.readTree(utf8Body(list))
                .get("data").get("total").intValue());
    }

    @Test
    void 导出openspec为zip_含附件() throws Exception {
        String id = create(RequirementTestFixtures.fullForm());
        mockMvc.perform(post("/requirements/{id}/submit", id)).andExpect(status().isOk());
        mockMvc.perform(multipart("/requirements/{id}/attachments", id)
                        .file(new MockMultipartFile("file", "shot.png", "image/png",
                                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})))
                .andExpect(status().isOk());
        mockMvc.perform(post("/requirements/{id}/render", id)
                        .contentType("application/json").content("{\"target\":\"openspec\"}"))
                .andExpect(status().isOk());

        MvcResult zip = mockMvc.perform(get("/requirements/{id}/export", id)
                        .param("target", "openspec"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("application/zip", zip.getResponse().getContentType());
        byte[] body = zip.getResponse().getContentAsByteArray();
        assertTrue(body.length > 100, "zip 体过小");
        assertTrue(containsEntry(body, "attachments/shot.png"), "zip 必须含附件");
        assertTrue(containsEntry(body, "proposal.md"), "zip 必须含变更包");
    }

    @Test
    void 附件非法格式400_魔数不符400() throws Exception {
        String id = create(RequirementTestFixtures.fullForm());
        mockMvc.perform(multipart("/requirements/{id}/attachments", id)
                        .file(new MockMultipartFile("file", "tool.exe", "application/x-exe", new byte[]{1})))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/requirements/{id}/attachments", id)
                        .file(new MockMultipartFile("file", "fake.png", "image/png", "MZfake".getBytes())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 编辑后导出带过期警示头() throws Exception {
        String id = create(RequirementTestFixtures.fullForm());
        mockMvc.perform(post("/requirements/{id}/submit", id)).andExpect(status().isOk());
        mockMvc.perform(post("/requirements/{id}/render", id)
                        .contentType("application/json").content("{\"target\":\"vibecoding\"}"))
                .andExpect(status().isOk());
        // 编辑（回退 DRAFT + 工件过期）→ 再提交 → 直接导出旧工件（未重渲染）
        RequirementForm edited = RequirementTestFixtures.fullForm();
        edited.getBasic().setTitle("订单导出（改）");
        mockMvc.perform(put("/requirements/{id}", id)
                        .contentType("application/json").content(json.writeValueAsString(edited)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/requirements/{id}/submit", id)).andExpect(status().isOk());
        MvcResult exported = mockMvc.perform(get("/requirements/{id}/export", id)
                        .param("target", "vibecoding"))
                .andExpect(status().isOk()).andReturn();
        assertEquals("true", exported.getResponse().getHeader("X-Artifact-Stale"), "过期警示头必须为 true");
    }

    // ---------- 小工具 ----------

    /** standalone MockMvc 默认 ISO-8859-1 解码，中文断言必须显式 UTF-8 */
    private String utf8Body(MvcResult result) throws java.io.UnsupportedEncodingException {
        return new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String create(RequirementForm form) throws Exception {
        MvcResult result = mockMvc.perform(post("/requirements")
                        .contentType("application/json").content(json.writeValueAsString(form)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(utf8Body(result)).get("data").get("id").asText();
    }

    /** zip 内查条目名（本地文件头签名 PK\x03\x04 后紧跟文件名） */
    private boolean containsEntry(byte[] zipBytes, String entryName) {
        byte[] name = entryName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] signature = {0x50, 0x4B, 0x03, 0x04};
        outer:
        for (int i = 0; i <= zipBytes.length - 30 - name.length; i++) {
            for (int j = 0; j < 4; j++) {
                if (zipBytes[i + j] != signature[j]) {
                    continue outer;
                }
            }
            int nameLength = ((zipBytes[i + 27] & 0xFF) << 8) | (zipBytes[i + 26] & 0xFF);
            if (nameLength == name.length) {
                boolean match = true;
                for (int k = 0; k < name.length; k++) {
                    if (zipBytes[i + 30 + k] != name[k]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return true;
                }
            }
        }
        return false;
    }
}
