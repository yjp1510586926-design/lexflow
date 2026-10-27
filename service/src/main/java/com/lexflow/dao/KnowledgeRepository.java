package com.lexflow.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeRepository {

    public record BaseRow(long id, Long caseId, String name, String status, String scope) {
        public BaseRow(long id, long caseId, String name, String status) {
            this(id, Long.valueOf(caseId), name, status, "CASE");
        }
    }

    public record SourceRow(
        long taskId,
        long fileVersionId,
        String fileName,
        int fileVersion,
        String sha256,
        String text,
        String segmentsJson
    ) {}

    public record DocumentRow(
        long id,
        long fileVersionId,
        long parseTaskId,
        String fileName,
        int fileVersion,
        String status,
        int chunkCount,
        Long pendingTaskId
    ) {}

    public record ChunkRow(
        long id,
        int chunkNo,
        String kind,
        int locatorIndex,
        int startOffset,
        int endOffset,
        String text
    ) {}

    public record IndexDocumentRow(
        long id,
        long baseId,
        Long caseId,
        long fileVersionId,
        String status,
        int indexVersion,
        int chunkCount,
        int lastIndexVersion,
        Long pendingTaskId
    ) {}

    public record SearchSourceRow(
        long documentId,
        int indexVersion,
        String collectionName,
        int dimension,
        String modelIdentity
    ) {}

    public record CitationRow(
        long chunkId,
        long documentId,
        long fileId,
        long fileVersionId,
        String fileName,
        int fileVersion,
        String locatorKind,
        int locatorIndex,
        int startOffset,
        int endOffset,
        String text
    ) {}

    public record NewChunk(
        int chunkNo,
        String kind,
        int locatorIndex,
        int startOffset,
        int endOffset,
        String text
    ) {}

    /** 历史答案展示和再次传给模型前，逐条引用须匹配仍发布的索引、可用来源文件及原摘要。 */
    public CitationRow liveCitation(long tenantId, long baseId, Long caseId, long chunkId) {
        return jdbc.query(
            """
            SELECT c.id,d.id,f.id,v.id,f.display_name,v.version_no,c.locator_kind,
                   c.locator_index,c.start_offset,c.end_offset,c.content_text
            FROM knowledge_chunk c
            JOIN knowledge_document d ON d.tenant_id=c.tenant_id AND d.id=c.knowledge_document_id
              AND d.index_version=c.index_version AND d.status='ACTIVE'
            JOIN knowledge_base b ON b.tenant_id=d.tenant_id AND b.id=d.knowledge_base_id
              AND b.case_id<=>? AND b.status='ACTIVE'
            JOIN file_version v ON v.tenant_id=d.tenant_id AND v.id=d.file_version_id
            JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
              AND ((b.scope='CASE' AND f.scope='CASE' AND f.case_id=b.case_id AND f.knowledge_base_id IS NULL)
                OR (b.scope='TENANT' AND b.case_id IS NULL AND f.scope='TENANT' AND f.case_id IS NULL AND f.knowledge_base_id=b.id))
              AND f.status='AVAILABLE'
            WHERE c.tenant_id=? AND d.knowledge_base_id=? AND c.id=? AND v.sha256=d.source_sha256
            """,
            resultSet ->
                resultSet.next()
                    ? new CitationRow(
                          resultSet.getLong(1),
                          resultSet.getLong(2),
                          resultSet.getLong(3),
                          resultSet.getLong(4),
                          resultSet.getString(5),
                          resultSet.getInt(6),
                          resultSet.getString(7),
                          resultSet.getInt(8),
                          resultSet.getInt(9),
                          resultSet.getInt(10),
                          resultSet.getString(11)
                      )
                    : null,
            caseId,
            tenantId,
            baseId,
            chunkId
        );
    }

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert baseInsert;
    private final SimpleJdbcInsert documentInsert;

    public KnowledgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.baseInsert = new SimpleJdbcInsert(jdbc)
            .withTableName("knowledge_base")
            .usingColumns("tenant_id", "case_id", "name", "scope", "status", "created_by")
            .usingGeneratedKeyColumns("id");
        this.documentInsert = new SimpleJdbcInsert(jdbc)
            .withTableName("knowledge_document")
            .usingColumns(
                "tenant_id",
                "knowledge_base_id",
                "file_version_id",
                "parse_task_id",
                "index_version",
                "source_sha256",
                "status",
                "chunk_count",
                "created_by"
            )
            .usingGeneratedKeyColumns("id");
    }

    public long createBase(long tenantId, long caseId, String name, long actorId) {
        return baseInsert
            .executeAndReturnKey(
                Map.of(
                    "tenant_id",
                    tenantId,
                    "case_id",
                    caseId,
                    "name",
                    name,
                    "scope",
                    "CASE",
                    "status",
                    "ACTIVE",
                    "created_by",
                    actorId
                )
            )
            .longValue();
    }

    public long createTenantBase(long tenant, String name, long actor) {
        var values = new HashMap<String, Object>();
        values.put("tenant_id", tenant);
        values.put("case_id", null);
        values.put("name", name);
        values.put("scope", "TENANT");
        values.put("status", "ACTIVE");
        values.put("created_by", actor);
        return baseInsert.executeAndReturnKey(values).longValue();
    }

    public List<BaseRow> tenantBases(long tenant) {
        return jdbc.query(
            "SELECT id,case_id,name,status,scope FROM knowledge_base WHERE tenant_id=? AND scope='TENANT' AND case_id IS NULL AND status='ACTIVE' ORDER BY id DESC",
            (resultSet, rowNumber) ->
                new BaseRow(
                    resultSet.getLong(1),
                    null,
                    resultSet.getString(3),
                    resultSet.getString(4),
                    resultSet.getString(5)
                ),
            tenant
        );
    }

    public SourceRow tenantSource(long tenant, long base, long task) {
        return jdbc.query(
            """
            SELECT t.id,t.resource_version_id,f.display_name,v.version_no,r.source_sha256,
                   r.content_text,CAST(r.segments_json AS CHAR)
            FROM async_task t JOIN task_parse_result r ON r.tenant_id=t.tenant_id AND r.task_id=t.id
            JOIN file_version v ON v.tenant_id=t.tenant_id AND v.id=t.resource_version_id
            JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
              AND f.scope='TENANT' AND f.case_id IS NULL AND f.knowledge_base_id=? AND f.status='AVAILABLE'
            JOIN knowledge_base b ON b.tenant_id=f.tenant_id AND b.id=f.knowledge_base_id AND b.scope='TENANT'
              AND b.case_id IS NULL AND b.status='ACTIVE'
            WHERE t.tenant_id=? AND t.case_id IS NULL AND t.id=? AND t.type='DOCUMENT_PARSE'
              AND t.status='SUCCEEDED' AND r.source_sha256=v.sha256 FOR UPDATE
            """,
            resultSet ->
                resultSet.next()
                    ? new SourceRow(
                          resultSet.getLong(1),
                          resultSet.getLong(2),
                          resultSet.getString(3),
                          resultSet.getInt(4),
                          resultSet.getString(5),
                          resultSet.getString(6),
                          resultSet.getString(7)
                      )
                    : null,
            base,
            tenant,
            task
        );
    }

    public BaseRow base(long tenantId, long id) {
        return jdbc.query(
            "SELECT id,case_id,name,status,scope FROM knowledge_base WHERE tenant_id=? AND id=?",
            resultSet ->
                resultSet.next()
                    ? new BaseRow(
                          resultSet.getLong(1),
                          resultSet.getObject(2, Long.class),
                          resultSet.getString(3),
                          resultSet.getString(4),
                          resultSet.getString(5)
                      )
                    : null,
            tenantId,
            id
        );
    }

    public List<BaseRow> bases(long tenantId, long caseId) {
        return jdbc.query(
            "SELECT id,case_id,name,status,scope FROM knowledge_base WHERE tenant_id=? AND case_id=? AND scope='CASE' AND status='ACTIVE' ORDER BY id DESC",
            (resultSet, row) ->
                new BaseRow(
                    resultSet.getLong(1),
                    resultSet.getObject(2, Long.class),
                    resultSet.getString(3),
                    resultSet.getString(4),
                    resultSet.getString(5)
                ),
            tenantId,
            caseId
        );
    }

    public SourceRow source(long tenantId, long caseId, long parseTaskId) {
        return jdbc.query(
            """
            SELECT t.id,t.resource_version_id,f.display_name,v.version_no,r.source_sha256,
                   r.content_text,CAST(r.segments_json AS CHAR)
            FROM async_task t
            JOIN task_parse_result r ON r.tenant_id=t.tenant_id AND r.task_id=t.id
            JOIN file_version v ON v.tenant_id=t.tenant_id AND v.id=t.resource_version_id
            JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id AND f.case_id=t.case_id AND f.scope='CASE' AND f.knowledge_base_id IS NULL
            WHERE t.tenant_id=? AND t.case_id=? AND t.id=? AND t.type='DOCUMENT_PARSE'
              AND t.status='SUCCEEDED' AND f.status='AVAILABLE' AND r.source_sha256=v.sha256
            FOR UPDATE
            """,
            resultSet ->
                resultSet.next()
                    ? new SourceRow(
                          resultSet.getLong(1),
                          resultSet.getLong(2),
                          resultSet.getString(3),
                          resultSet.getInt(4),
                          resultSet.getString(5),
                          resultSet.getString(6),
                          resultSet.getString(7)
                      )
                    : null,
            tenantId,
            caseId,
            parseTaskId
        );
    }

    public DocumentRow documentForVersion(long tenantId, long baseId, long fileVersionId) {
        return jdbc.query(
            """
            SELECT d.id,d.file_version_id,d.parse_task_id,f.display_name,v.version_no,d.status,d.chunk_count,d.pending_index_task_id
            FROM knowledge_document d
            JOIN file_version v ON v.tenant_id=d.tenant_id AND v.id=d.file_version_id
            JOIN knowledge_base b ON b.tenant_id=d.tenant_id AND b.id=d.knowledge_base_id AND b.status='ACTIVE'
            JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id AND f.status='AVAILABLE'
              AND ((b.scope='CASE' AND f.scope='CASE' AND f.case_id=b.case_id AND f.knowledge_base_id IS NULL)
                OR (b.scope='TENANT' AND b.case_id IS NULL AND f.scope='TENANT' AND f.case_id IS NULL AND f.knowledge_base_id=b.id))
            WHERE d.tenant_id=? AND d.knowledge_base_id=? AND d.file_version_id=? AND v.sha256=d.source_sha256
            """,
            resultSet -> resultSet.next() ? documentRow(resultSet) : null,
            tenantId,
            baseId,
            fileVersionId
        );
    }

    public long createDocument(
        long tenantId,
        long baseId,
        SourceRow source,
        int chunkCount,
        long actorId
    ) {
        return documentInsert
            .executeAndReturnKey(
                Map.of(
                    "tenant_id",
                    tenantId,
                    "knowledge_base_id",
                    baseId,
                    "file_version_id",
                    source.fileVersionId(),
                    "parse_task_id",
                    source.taskId(),
                    "index_version",
                    1,
                    "source_sha256",
                    source.sha256(),
                    "status",
                    "PREPARED",
                    "chunk_count",
                    chunkCount,
                    "created_by",
                    actorId
                )
            )
            .longValue();
    }

    public List<DocumentRow> documents(long tenantId, long baseId) {
        return jdbc.query(
            """
            SELECT d.id,d.file_version_id,d.parse_task_id,f.display_name,v.version_no,d.status,d.chunk_count,d.pending_index_task_id
            FROM knowledge_document d
            JOIN file_version v ON v.tenant_id=d.tenant_id AND v.id=d.file_version_id
            JOIN knowledge_base b ON b.tenant_id=d.tenant_id AND b.id=d.knowledge_base_id AND b.status='ACTIVE'
            JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id AND f.status='AVAILABLE'
              AND ((b.scope='CASE' AND f.scope='CASE' AND f.case_id=b.case_id AND f.knowledge_base_id IS NULL)
                OR (b.scope='TENANT' AND b.case_id IS NULL AND f.scope='TENANT' AND f.case_id IS NULL AND f.knowledge_base_id=b.id))
            WHERE d.tenant_id=? AND d.knowledge_base_id=? AND v.sha256=d.source_sha256 ORDER BY d.id DESC
            """,
            (resultSet, row) -> documentRow(resultSet),
            tenantId,
            baseId
        );
    }

    private static DocumentRow documentRow(ResultSet resultSet) throws SQLException {
        return new DocumentRow(
            resultSet.getLong(1),
            resultSet.getLong(2),
            resultSet.getLong(3),
            resultSet.getString(4),
            resultSet.getInt(5),
            resultSet.getString(6),
            resultSet.getInt(7),
            resultSet.getObject(8, Long.class)
        );
    }

    public void insertChunks(long tenantId, long documentId, List<NewChunk> chunks) {
        jdbc.batchUpdate(
            """
            INSERT INTO knowledge_chunk(tenant_id,knowledge_document_id,index_version,chunk_no,
                locator_kind,locator_index,start_offset,end_offset,content_text)
            VALUES(?,?,1,?,?,?,?,?,?)
            """,
            chunks,
            500,
            (ps, chunk) -> {
                ps.setLong(1, tenantId);
                ps.setLong(2, documentId);
                ps.setInt(3, chunk.chunkNo());
                ps.setString(4, chunk.kind());
                ps.setInt(5, chunk.locatorIndex());
                ps.setInt(6, chunk.startOffset());
                ps.setInt(7, chunk.endOffset());
                ps.setString(8, chunk.text());
            }
        );
    }

    public List<ChunkRow> chunks(long tenantId, long baseId, long documentId) {
        return jdbc.query(
            """
            SELECT c.id,c.chunk_no,c.locator_kind,c.locator_index,c.start_offset,c.end_offset,c.content_text
            FROM knowledge_chunk c
            JOIN knowledge_document d ON d.tenant_id=c.tenant_id AND d.id=c.knowledge_document_id
              AND d.index_version=c.index_version AND d.knowledge_base_id=?
            JOIN file_version v ON v.tenant_id=d.tenant_id AND v.id=d.file_version_id
            JOIN knowledge_base b ON b.tenant_id=d.tenant_id AND b.id=d.knowledge_base_id AND b.status='ACTIVE'
            JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id AND f.status='AVAILABLE'
              AND ((b.scope='CASE' AND f.scope='CASE' AND f.case_id=b.case_id AND f.knowledge_base_id IS NULL)
                OR (b.scope='TENANT' AND b.case_id IS NULL AND f.scope='TENANT' AND f.case_id IS NULL AND f.knowledge_base_id=b.id))
            WHERE c.tenant_id=? AND c.knowledge_document_id=? AND v.sha256=d.source_sha256 ORDER BY c.chunk_no
            """,
            (resultSet, row) ->
                new ChunkRow(
                    resultSet.getLong(1),
                    resultSet.getInt(2),
                    resultSet.getString(3),
                    resultSet.getInt(4),
                    resultSet.getInt(5),
                    resultSet.getInt(6),
                    resultSet.getString(7)
                ),
            baseId,
            tenantId,
            documentId
        );
    }

    public IndexDocumentRow indexDocument(long tenantId, long baseId, long documentId) {
        return jdbc.query(
            """
            SELECT d.id,d.knowledge_base_id,b.case_id,d.file_version_id,d.status,d.index_version,d.chunk_count,d.last_index_version,d.pending_index_task_id
            FROM knowledge_document d
            JOIN knowledge_base b ON b.tenant_id=d.tenant_id AND b.id=d.knowledge_base_id AND b.status='ACTIVE'
            JOIN file_version v ON v.tenant_id=d.tenant_id AND v.id=d.file_version_id
            JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
              AND ((b.scope='CASE' AND f.scope='CASE' AND f.case_id=b.case_id AND f.knowledge_base_id IS NULL)
                OR (b.scope='TENANT' AND b.case_id IS NULL AND f.scope='TENANT' AND f.case_id IS NULL AND f.knowledge_base_id=b.id))
              AND f.status='AVAILABLE'
            WHERE d.tenant_id=? AND d.knowledge_base_id=? AND d.id=? AND v.sha256=d.source_sha256
            FOR UPDATE
            """,
            resultSet ->
                resultSet.next()
                    ? new IndexDocumentRow(
                          resultSet.getLong(1),
                          resultSet.getLong(2),
                          resultSet.getObject(3, Long.class),
                          resultSet.getLong(4),
                          resultSet.getString(5),
                          resultSet.getInt(6),
                          resultSet.getInt(7),
                          resultSet.getInt(8),
                          resultSet.getObject(9, Long.class)
                      )
                    : null,
            tenantId,
            baseId,
            documentId
        );
    }

    /** 先锁定同一知识文档，再分配递增索引工作版本；调用方须在同一事务内完成后续登记。 */
    public boolean lockDocument(long tenantId, long baseId, long documentId) {
        return jdbc.query(
            "SELECT id FROM knowledge_document WHERE tenant_id=? AND knowledge_base_id=? AND id=? FOR UPDATE",
            resultSet -> {
                return resultSet.next();
            },
            tenantId,
            baseId,
            documentId
        );
    }

    public int cloneIndexChunks(long tenantId, long documentId, int fromVersion, int toVersion) {
        return jdbc.update(
            """
            INSERT INTO knowledge_chunk(tenant_id,knowledge_document_id,index_version,chunk_no,
                locator_kind,locator_index,start_offset,end_offset,content_text)
            SELECT tenant_id,knowledge_document_id,?,chunk_no,locator_kind,locator_index,
                start_offset,end_offset,content_text FROM knowledge_chunk
            WHERE tenant_id=? AND knowledge_document_id=? AND index_version=? ORDER BY chunk_no
            """,
            toVersion,
            tenantId,
            documentId,
            fromVersion
        );
    }

    public int beginIndex(
        long tenantId,
        long documentId,
        long taskId,
        int indexVersion,
        int previousVersion
    ) {
        return jdbc.update(
            """
            UPDATE knowledge_document SET status=IF(status='ACTIVE','ACTIVE','INDEXING'),
                pending_index_task_id=?,last_index_version=?
            WHERE tenant_id=? AND id=? AND status IN ('PREPARED','FAILED','ACTIVE')
                AND pending_index_task_id IS NULL AND last_index_version=?
            """,
            taskId,
            indexVersion,
            tenantId,
            documentId,
            previousVersion
        );
    }

    public int finishIndex(
        long tenantId,
        long documentId,
        long taskId,
        int indexVersion,
        boolean success,
        String collection,
        Integer dimension,
        String modelIdentity
    ) {
        if (!success) {
            return jdbc.update(
                """
                UPDATE knowledge_document SET status=IF(status='ACTIVE','ACTIVE','FAILED'),pending_index_task_id=NULL
                WHERE tenant_id=? AND id=? AND pending_index_task_id=? AND last_index_version=?
                    AND status IN ('ACTIVE','INDEXING')
                """,
                tenantId,
                documentId,
                taskId,
                indexVersion
            );
        }
        return jdbc.update(
            """
            UPDATE knowledge_document SET status='ACTIVE',collection_name=?,embedding_dimension=?,
                model_identity=?,index_version=?,pending_index_task_id=NULL
            WHERE tenant_id=? AND id=? AND pending_index_task_id=? AND last_index_version=?
                AND status IN ('ACTIVE','INDEXING')
            """,
            collection,
            dimension,
            modelIdentity,
            indexVersion,
            tenantId,
            documentId,
            taskId,
            indexVersion
        );
    }

    public List<ChunkRow> indexChunks(long tenantId, long documentId, int indexVersion) {
        return jdbc.query(
            """
            SELECT id,chunk_no,locator_kind,locator_index,start_offset,end_offset,content_text
            FROM knowledge_chunk WHERE tenant_id=? AND knowledge_document_id=? AND index_version=?
            ORDER BY chunk_no
            """,
            (resultSet, row) ->
                new ChunkRow(
                    resultSet.getLong(1),
                    resultSet.getInt(2),
                    resultSet.getString(3),
                    resultSet.getInt(4),
                    resultSet.getInt(5),
                    resultSet.getInt(6),
                    resultSet.getString(7)
                ),
            tenantId,
            documentId,
            indexVersion
        );
    }

    /** 仅从已发布且来源仍可用、摘要未变化的文档中选择向量检索位置。 */
    public List<SearchSourceRow> searchSources(long tenantId, long baseId, Long caseId) {
        return jdbc.query(
            """
            SELECT d.id,d.index_version,d.collection_name,d.embedding_dimension,d.model_identity
            FROM knowledge_document d
            JOIN knowledge_base b ON b.tenant_id=d.tenant_id AND b.id=d.knowledge_base_id
              AND b.case_id<=>? AND b.status='ACTIVE'
            JOIN file_version v ON v.tenant_id=d.tenant_id AND v.id=d.file_version_id
            JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
              AND ((b.scope='CASE' AND f.scope='CASE' AND f.case_id=b.case_id AND f.knowledge_base_id IS NULL)
                OR (b.scope='TENANT' AND b.case_id IS NULL AND f.scope='TENANT' AND f.case_id IS NULL AND f.knowledge_base_id=b.id))
              AND f.status='AVAILABLE'
            WHERE d.tenant_id=? AND d.knowledge_base_id=? AND d.status='ACTIVE'
              AND v.sha256=d.source_sha256 AND d.collection_name IS NOT NULL
              AND d.embedding_dimension IS NOT NULL AND d.model_identity IS NOT NULL
            ORDER BY d.id LIMIT 1001
            """,
            (resultSet, row) ->
                new SearchSourceRow(
                    resultSet.getLong(1),
                    resultSet.getInt(2),
                    resultSet.getString(3),
                    resultSet.getInt(4),
                    resultSet.getString(5)
                ),
            caseId,
            tenantId,
            baseId
        );
    }

    /** Milvus 命中仅提供候选标识；须在 MySQL 复核租户归属、当前发布版本及来源后才能返回正文。 */
    public CitationRow citation(
        long tenantId,
        long baseId,
        Long caseId,
        long documentId,
        long chunkId,
        String collectionName,
        int indexVersion
    ) {
        return jdbc.query(
            """
            SELECT c.id,d.id,f.id,v.id,f.display_name,v.version_no,c.locator_kind,
                   c.locator_index,c.start_offset,c.end_offset,c.content_text
            FROM knowledge_chunk c
            JOIN knowledge_document d ON d.tenant_id=c.tenant_id AND d.id=c.knowledge_document_id
              AND d.index_version=c.index_version AND d.status='ACTIVE'
              AND d.collection_name=? AND d.index_version=?
            JOIN knowledge_base b ON b.tenant_id=d.tenant_id AND b.id=d.knowledge_base_id
              AND b.case_id<=>? AND b.status='ACTIVE'
            JOIN file_version v ON v.tenant_id=d.tenant_id AND v.id=d.file_version_id
            JOIN file_object f ON f.tenant_id=v.tenant_id AND f.id=v.file_id
              AND ((b.scope='CASE' AND f.scope='CASE' AND f.case_id=b.case_id AND f.knowledge_base_id IS NULL)
                OR (b.scope='TENANT' AND b.case_id IS NULL AND f.scope='TENANT' AND f.case_id IS NULL AND f.knowledge_base_id=b.id))
              AND f.status='AVAILABLE'
            WHERE c.tenant_id=? AND d.knowledge_base_id=? AND d.id=? AND c.id=?
              AND v.sha256=d.source_sha256
            """,
            resultSet ->
                resultSet.next()
                    ? new CitationRow(
                          resultSet.getLong(1),
                          resultSet.getLong(2),
                          resultSet.getLong(3),
                          resultSet.getLong(4),
                          resultSet.getString(5),
                          resultSet.getInt(6),
                          resultSet.getString(7),
                          resultSet.getInt(8),
                          resultSet.getInt(9),
                          resultSet.getInt(10),
                          resultSet.getString(11)
                      )
                    : null,
            collectionName,
            indexVersion,
            caseId,
            tenantId,
            baseId,
            documentId,
            chunkId
        );
    }
}
