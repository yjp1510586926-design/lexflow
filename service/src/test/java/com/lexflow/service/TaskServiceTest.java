package com.lexflow.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.dao.AsyncTaskMapper;
import com.lexflow.dao.AuditLogMapper;
import com.lexflow.dao.OutboxEventMapper;
import com.lexflow.dao.SysRoleMapper;
import com.lexflow.dao.TaskParseResultMapper;
import com.lexflow.dao.model.AsyncTaskModel;
import com.lexflow.dao.model.AuditLogModel;
import com.lexflow.dao.model.OutboxEventModel;
import com.lexflow.service.identity.Identity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private AsyncTaskMapper taskMapper;

    @Mock
    private SysRoleMapper sysRoleMapper;

    @Mock
    private AuditLogMapper auditLogMapper;

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @Mock
    private com.lexflow.service.CaseService cases;

    private TaskService service;
    private final Identity identity = new Identity(100L, 1L, 1L, "test_lawyer");

    @BeforeEach
    void setUp() {
        service = new TaskService(
            taskMapper,
            sysRoleMapper,
            auditLogMapper,
            outboxEventMapper,
            new ObjectMapper(),
            mock(TaskParseResultMapper.class),
            cases,
            mock(com.lexflow.service.KnowledgeAccessService.class)
        );
    }

    @Test
    void submitTask_outboxPatternSuccess() {
        long caseId = 88L;
        String type = "DOCUMENT_PARSE";
        String idempotencyKey = "idem-key-001";

        // 模拟幂等检查无重复
        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        when(taskMapper.countAvailableSource(1L, caseId, 12L)).thenReturn(1);

        // 模拟插入 async_task 成功生成自增 ID
        doAnswer(invocation -> {
            AsyncTaskModel m = invocation.getArgument(0);
            m.setId(999L);
            return 1;
        })
            .when(taskMapper)
            .insert(any(AsyncTaskModel.class));

        when(outboxEventMapper.insert(any(OutboxEventModel.class))).thenReturn(1);
        when(auditLogMapper.insert(any(AuditLogModel.class))).thenReturn(1);

        var taskView = service.submitTask(identity, caseId, type, 12L, idempotencyKey);

        assertNotNull(taskView);
        assertEquals("999", taskView.id());
        assertEquals("QUEUED", taskView.status());
        assertEquals(type, taskView.type());
        verify(cases).require(identity, "task:read");
        verify(cases).authorize(
            identity,
            caseId,
            com.lexflow.service.permission.CaseAccessPolicy.Action.READ,
            "file:read",
            false
        );

        // 验证 Outbox 模式：插入了对应 routingKey 的事件
        ArgumentCaptor<OutboxEventModel> eventCaptor = ArgumentCaptor.forClass(
            OutboxEventModel.class
        );
        verify(outboxEventMapper).insert(eventCaptor.capture());
        assertEquals("document.parse.requested.v1", eventCaptor.getValue().getRoutingKey());
        assertEquals("PENDING", eventCaptor.getValue().getStatus());
        assertNull(eventCaptor.getValue().getNextRetryAt(), "首次投递使用数据库 CURRENT_TIMESTAMP");
        assertEquals(999L, eventCaptor.getValue().getTaskId());

        verify(auditLogMapper).insert(any(AuditLogModel.class));
    }

    @Test
    void submitTask_idempotencyReturnsExisting() {
        long caseId = 88L;
        String type = "DOCUMENT_PARSE";
        String idempotencyKey = "duplicate-key";

        AsyncTaskModel existing = AsyncTaskModel.builder()
            .id(777L)
            .createdBy(100L)
            .tenantId(1L)
            .caseId(caseId)
            .resourceVersionId(12L)
            .type(type)
            .status("RUNNING")
            .idempotencyKey(idempotencyKey)
            .build();

        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        var taskView = service.submitTask(identity, caseId, type, 12L, idempotencyKey);

        assertEquals("777", taskView.id());
        assertEquals("RUNNING", taskView.status());
        // 幂等命中时不应该重复插入 task 或 outbox_event
        verify(taskMapper, never()).insert(any(AsyncTaskModel.class));
        verify(outboxEventMapper, never()).insert(any(OutboxEventModel.class));
    }

    @Test
    void sameKeyWithDifferentInputDoesNotReturnOldTask() {
        var existing = AsyncTaskModel.builder()
            .id(777L)
            .tenantId(1L)
            .createdBy(100L)
            .caseId(88L)
            .type("DOCUMENT_PARSE")
            .resourceVersionId(10L)
            .build();
        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            service.submitTask(identity, 88L, "DOCUMENT_PARSE", 11L, "same-key")
        );
        existing.setResourceVersionId(11L);
        existing.setCreatedBy(200L);
        assertThrows(com.lexflow.framework.exception.BusinessException.class, () ->
            service.submitTask(identity, 88L, "DOCUMENT_PARSE", 11L, "same-key")
        );
        verify(taskMapper, never()).insert(any(AsyncTaskModel.class));
        verifyNoInteractions(outboxEventMapper);
    }

    @Test
    void unavailableOrMissingSourceDoesNotCreateTask() {
        assertThrows(IllegalArgumentException.class, () ->
            service.submitTask(identity, null, "DOCUMENT_PARSE", null, "missing")
        );
        assertThrows(com.lexflow.service.permission.AccessDenied.class, () ->
            service.submitTask(identity, 88L, "DOCUMENT_PARSE", 12L, "unavailable")
        );
        verify(taskMapper, never()).insert(any(AsyncTaskModel.class));
        verifyNoInteractions(outboxEventMapper);
    }

    @Test
    void requestCancel_terminalTaskFails() {
        long taskId = 500L;
        AsyncTaskModel terminalTask = AsyncTaskModel.builder()
            .id(taskId)
            .tenantId(1L)
            .caseId(88L)
            .status("SUCCEEDED")
            .createdBy(100L)
            .build();

        when(taskMapper.selectByIdForUpdate(1L, taskId)).thenReturn(terminalTask);

        assertThrows(IllegalStateException.class, () -> service.requestCancel(identity, taskId));
        verify(taskMapper, never()).requestCancel(anyLong(), anyLong(), anyInt());
    }

    @Test
    void requestCancel_success() {
        long taskId = 500L;
        AsyncTaskModel runningTask = AsyncTaskModel.builder()
            .id(taskId)
            .tenantId(1L)
            .caseId(88L)
            .status("RUNNING")
            .stateVersion(1)
            .createdBy(100L)
            .build();

        AsyncTaskModel cancelRequestedTask = AsyncTaskModel.builder()
            .id(taskId)
            .tenantId(1L)
            .caseId(88L)
            .status("CANCEL_REQUESTED")
            .stateVersion(2)
            .createdBy(100L)
            .build();

        when(taskMapper.selectByIdForUpdate(1L, taskId)).thenReturn(runningTask);
        when(taskMapper.requestCancel(1L, taskId, 1)).thenReturn(1);
        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(cancelRequestedTask);

        var result = service.requestCancel(identity, taskId);

        assertEquals("CANCEL_REQUESTED", result.status());
        verify(auditLogMapper).insert(any(AuditLogModel.class));
    }

    @Test
    void taskTimesCarryUtcOffsetAndKeepUnknownTimesNull() {
        var created = java.time.LocalDateTime.of(2026, 10, 8, 18, 16, 28, 123456000);
        var model = AsyncTaskModel.builder()
            .id(777L)
            .tenantId(1L)
            .createdBy(100L)
            .caseId(88L)
            .type("DOCUMENT_PARSE")
            .resourceVersionId(12L)
            .status("SUCCEEDED")
            .createdAt(created)
            .startedAt(created.plusMinutes(1))
            .finishedAt(created.plusMinutes(2))
            .build();
        when(taskMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(model);
        var view = service.get(identity, 777L);
        assertEquals(
            java.time.Instant.parse("2026-10-08T18:16:28.123456Z"),
            java.time.Instant.parse(view.createdAt())
        );
        assertEquals("2026-10-08T18:17:28.123456Z", view.startedAt());
        assertEquals("2026-10-08T18:18:28.123456Z", view.finishedAt());
        model.setCreatedAt(null);
        model.setStartedAt(null);
        model.setFinishedAt(null);
        var unknown = service.get(identity, 777L);
        assertNull(unknown.createdAt());
        assertNull(unknown.startedAt());
        assertNull(unknown.finishedAt());
    }
}
