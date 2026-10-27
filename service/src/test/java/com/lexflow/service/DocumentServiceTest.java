package com.lexflow.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.DocumentMapper;
import com.lexflow.dao.DocumentVersionMapper;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.DocumentModel;
import com.lexflow.dao.model.DocumentVersionModel;
import com.lexflow.service.identity.Identity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentVersionMapper versionMapper;

    @Mock
    private com.lexflow.service.CaseService cases;

    @Mock
    private AuditLogMapper auditLogMapper;

    private DocumentService service;
    private final Identity identity = new Identity(100L, 1L, 1L, "test_user");

    @BeforeEach
    void setUp() {
        service = new DocumentService(documentMapper, versionMapper, cases, auditLogMapper);
    }

    @Test
    void staleEditorCannotSaveRestoreOrPublishEvenWhenItKnowsAnotherCurrentVersionId() {
        var live = DocumentModel.builder()
            .id(20L)
            .tenantId(1L)
            .caseId(10L)
            .status("DRAFT")
            .currentVersionId(99L)
            .version(4L)
            .build();
        when(documentMapper.lockCurrent(1L, 10L, 20L)).thenReturn(live);
        assertEquals(
            409,
            assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
                service.addVersion(identity, 10, 20, "未保存编辑", 3, 99)
            ).getCode()
        );
        assertEquals(
            409,
            assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
                service.addVersion(identity, 10, 20, "未保存编辑", 4, 98)
            ).getCode()
        );
        assertEquals(
            409,
            assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
                service.restoreVersion(identity, 10, 20, 50, 3, 98)
            ).getCode()
        );
        assertEquals(
            409,
            assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
                service.publish(identity, 10, 20, 3, 98)
            ).getCode()
        );
        verifyNoInteractions(versionMapper, auditLogMapper);
        verify(documentMapper, never()).update(any(), any());
    }

    @Test
    void invalidExpectedVersionsCannotBecomeAnUncheckedMutation() {
        assertEquals(
            400,
            assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
                service.addVersion(identity, 10, 20, "正文", -1, 99)
            ).getCode()
        );
        assertEquals(
            400,
            assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
                service.restoreVersion(identity, 10, 20, 50, 0, 0)
            ).getCode()
        );
        assertEquals(
            400,
            assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
                service.publish(identity, 10, 20, 0, 0)
            ).getCode()
        );
        verifyNoInteractions(documentMapper, versionMapper, auditLogMapper);
    }

    @Test
    void reviewVersion_approvedSuccess() {
        long caseId = 10L;
        long docId = 20L;
        long versionId = 30L;

        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(
            DocumentModel.builder().id(docId).tenantId(1L).caseId(caseId).version(1L).build()
        );

        // 模拟版本记录：AI_DRAFT 且 PENDING_REVIEW
        DocumentVersionModel version = DocumentVersionModel.builder()
            .id(versionId)
            .tenantId(1L)
            .documentId(docId)
            .versionNo(2)
            .contentText("AI 生成的起诉状草稿内容")
            .source("AI_DRAFT")
            .reviewStatus("PENDING_REVIEW")
            .createdBy(100L)
            .createdAt(LocalDateTime.now())
            .build();

        when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);
        when(versionMapper.updateById(any(DocumentVersionModel.class))).thenReturn(1);
        when(documentMapper.updateById(any(DocumentModel.class))).thenReturn(1);

        var result = service.reviewVersion(identity, caseId, docId, versionId, "APPROVED");

        assertNotNull(result);
        assertEquals("APPROVED", result.reviewStatus());
        assertEquals("AI_DRAFT", result.source());

        // 验证持久层调用与审计日志
        ArgumentCaptor<DocumentVersionModel> verCaptor = ArgumentCaptor.forClass(
            DocumentVersionModel.class
        );
        verify(versionMapper).updateById(verCaptor.capture());
        assertEquals("APPROVED", verCaptor.getValue().getReviewStatus());

        ArgumentCaptor<DocumentModel> docCaptor = ArgumentCaptor.forClass(DocumentModel.class);
        verify(documentMapper).updateById(docCaptor.capture());
        assertEquals(versionId, docCaptor.getValue().getCurrentVersionId());

        verify(auditLogMapper).insert(any(AuditLogModel.class));
    }

    @Test
    void reviewVersion_rejectedSuccess() {
        long caseId = 10L;
        long docId = 20L;
        long versionId = 30L;

        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(
            DocumentModel.builder().id(docId).tenantId(1L).caseId(caseId).version(1L).build()
        );

        DocumentVersionModel version = DocumentVersionModel.builder()
            .id(versionId)
            .tenantId(1L)
            .documentId(docId)
            .versionNo(3)
            .contentText("AI 待审查草稿")
            .source("AI_DRAFT")
            .reviewStatus("PENDING_REVIEW")
            .createdBy(100L)
            .createdAt(LocalDateTime.now())
            .build();

        when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);
        when(versionMapper.updateById(any(DocumentVersionModel.class))).thenReturn(1);

        var result = service.reviewVersion(identity, caseId, docId, versionId, "REJECTED");

        assertNotNull(result);
        assertEquals("REJECTED", result.reviewStatus());

        ArgumentCaptor<DocumentVersionModel> verCaptor = ArgumentCaptor.forClass(
            DocumentVersionModel.class
        );
        verify(versionMapper).updateById(verCaptor.capture());
        assertEquals("REJECTED", verCaptor.getValue().getReviewStatus());

        verify(documentMapper, never()).updateById(any(DocumentModel.class));
    }

    @Test
    void reviewVersion_manualVersionCannotBeReviewed() {
        long caseId = 10L;
        long docId = 20L;
        long versionId = 30L;

        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(
            DocumentModel.builder().id(docId).tenantId(1L).caseId(caseId).version(1L).build()
        );

        DocumentVersionModel manualVersion = DocumentVersionModel.builder()
            .id(versionId)
            .tenantId(1L)
            .documentId(docId)
            .versionNo(1)
            .contentText("手动编辑的起诉状")
            .source("MANUAL")
            .createdBy(100L)
            .createdAt(LocalDateTime.now())
            .build();

        when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(manualVersion);

        assertThrows(IllegalStateException.class, () ->
            service.reviewVersion(identity, caseId, docId, versionId, "APPROVED")
        );
    }

    @Test
    void getExportData_success() {
        long caseId = 10L;
        long docId = 20L;
        long currentVersionId = 99L;

        DocumentModel doc = DocumentModel.builder()
            .id(docId)
            .tenantId(1L)
            .caseId(caseId)
            .title("房屋租赁协议")
            .status("PUBLISHED")
            .currentVersionId(currentVersionId)
            .build();

        when(documentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(doc);

        DocumentVersionModel version = DocumentVersionModel.builder()
            .id(currentVersionId)
            .tenantId(1L)
            .documentId(docId)
            .versionNo(4)
            .contentText("甲方出租人：张三；乙方承租人：李四...")
            .build();

        when(versionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(version);

        var exportData = service.getExportData(identity, caseId, docId, currentVersionId);

        assertEquals("房屋租赁协议", exportData.title());
        assertEquals(4, exportData.versionNo());
        assertEquals("甲方出租人：张三；乙方承租人：李四...", exportData.content());
    }
}
