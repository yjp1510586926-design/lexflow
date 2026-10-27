package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Upgrade populated V24 data; no application scheduling, object or vendor I/O. */
@EnabledIfEnvironmentVariable(named = "LEXFLOW_LIFECYCLE_UPGRADE", matches = "true")
class LifecycleUpgradeIntegrationTest {

    @Test
    void upgradePreservesLegacyExportsAndBackfillsOnlyKnownVectorLocations() {
        String url = System.getenv("LEXFLOW_LIFECYCLE_DATABASE_URL"),
            user = System.getenv("MYSQL_USER"),
            password = System.getenv("MYSQL_PASSWORD");
        if (
            url == null ||
            !url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/lexflow_lifecycle_[a-z0-9]+\\?.+") ||
            user == null ||
            !user.matches("lifecycle_[a-z0-9]+")
        ) throw new IllegalStateException("迁移检查必须使用独立临时库与账户");
        var source = new DriverManagerDataSource(url, user, password);
        var db = new JdbcTemplate(source);
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
                Integer.class
            )
        );
        Flyway.configure()
            .dataSource(source)
            .locations("classpath:db/migration")
            .target("24")
            .load()
            .migrate();
        db.update("INSERT INTO tenant(id,name) VALUES(1,'upgrade synthetic tenant')");
        db.update(
            "INSERT INTO sys_user(id,username,password_hash,display_name) VALUES(2,'upgrade-synthetic','not-an-authenticatable-hash','Upgrade fixture')"
        );
        db.update("INSERT INTO tenant_user(tenant_id,user_id) VALUES(1,2)");
        db.update(
            "INSERT INTO case_info(id,tenant_id,case_no,title,case_type,owner_id) VALUES(3,1,'UPGRADE','Synthetic upgrade','CIVIL',2)"
        );
        db.update(
            "INSERT INTO document(id,tenant_id,case_id,title,created_by) VALUES(4,1,3,'Legacy export fixture',2)"
        );
        db.update(
            "INSERT INTO document_version(id,tenant_id,document_id,version_no,content_text,created_by) VALUES(5,1,4,1,'synthetic',2)"
        );
        db.update("UPDATE document SET current_version_id=5 WHERE id=4 AND tenant_id=1");
        String currentToken = UUID.randomUUID().toString(),
            oldToken = UUID.randomUUID().toString();
        String current = "tenants/1/exports/6/attempts/" + currentToken + ".txt",
            old = "tenants/1/exports/6/attempts/" + oldToken + ".txt";
        db.update(
            "INSERT INTO async_task(id,tenant_id,case_id,type,resource_version_id,status,idempotency_key,created_by,result_object_key) VALUES(6,1,3,'EXPORT',5,'SUCCEEDED','legacy-export',2,?)",
            current
        );
        db.update(
            "INSERT INTO document_export_job(tenant_id,task_id,document_id,document_version_id,title,version_no,document_status,source_sha256,size_bytes,claim_token,result_object_key) VALUES(1,6,4,5,'Legacy export fixture',1,'DRAFT',?,1,?,?)",
            "0".repeat(64),
            currentToken,
            current
        );
        db.update(
            "INSERT INTO document_export_artifact(token,tenant_id,task_id,object_key) VALUES(?,1,6,?)",
            currentToken,
            current
        );
        db.update(
            "INSERT INTO document_export_artifact(token,tenant_id,task_id,object_key,last_cleaned_at,next_cleanup_at) VALUES(?,1,6,?,TIMESTAMPADD(DAY,-2,NOW(6)),NOW(6))",
            oldToken,
            old
        );
        var oldCleanup = db.queryForObject(
            "SELECT last_cleaned_at FROM document_export_artifact WHERE token=?",
            java.sql.Timestamp.class,
            oldToken
        );
        db.update(
            "INSERT INTO file_object(id,tenant_id,case_id,display_name,status,created_by) VALUES(7,1,3,'Synthetic reference','AVAILABLE',2)"
        );
        for (int version = 1; version <= 2; version++) {
            long fileVersion = version == 1 ? 8 : 12,
                parse = version == 1 ? 9 : 13;
            db.update(
                "INSERT INTO file_version(id,tenant_id,file_id,version_no,object_key,sha256,size_bytes,mime_type) VALUES(?,1,7,?,?,?,1,'text/plain')",
                fileVersion,
                version,
                "tenants/1/cases/3/7/v" + version,
                "0".repeat(64)
            );
            db.update(
                "INSERT INTO async_task(id,tenant_id,case_id,type,resource_version_id,status,idempotency_key,created_by) VALUES(?,1,3,'DOCUMENT_PARSE',?,'SUCCEEDED',?,2)",
                parse,
                fileVersion,
                "parse-" + version
            );
        }
        db.update(
            "INSERT INTO knowledge_base(id,tenant_id,case_id,name,created_by) VALUES(10,1,3,'Upgrade synthetic references',2)"
        );
        db.update(
            "INSERT INTO knowledge_document(id,tenant_id,knowledge_base_id,file_version_id,parse_task_id,index_version,source_sha256,status,created_by,collection_name,embedding_dimension,model_identity) VALUES(11,1,10,8,9,2,?,'ACTIVE',2,'lexflow_upgrade_synthetic_known',2,'synthetic-model')",
            "0".repeat(64)
        );
        db.update(
            "INSERT INTO knowledge_document(id,tenant_id,knowledge_base_id,file_version_id,parse_task_id,index_version,source_sha256,status,created_by) VALUES(14,1,10,12,13,1,?,'PREPARED',2)",
            "0".repeat(64)
        );
        var flyway = Flyway.configure()
            .dataSource(source)
            .locations("classpath:db/migration")
            .load();
        assertEquals(4, flyway.migrate().migrationsExecuted);
        flyway.validate();
        assertEquals(
            28,
            db.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND type='SQL'",
                Integer.class
            )
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT retention_days FROM document_export_job WHERE tenant_id=1 AND task_id=6",
                Integer.class
            )
        );
        assertNull(
            db.queryForObject(
                "SELECT expires_at FROM document_export_job WHERE tenant_id=1 AND task_id=6",
                java.sql.Timestamp.class
            )
        );
        assertNull(
            db.queryForObject(
                "SELECT expired_at FROM document_export_job WHERE tenant_id=1 AND task_id=6",
                java.sql.Timestamp.class
            )
        );
        assertEquals(
            current,
            db.queryForObject(
                "SELECT result_object_key FROM document_export_job WHERE tenant_id=1 AND task_id=6",
                String.class
            )
        );
        assertEquals(
            current,
            db.queryForObject(
                "SELECT result_object_key FROM async_task WHERE tenant_id=1 AND id=6",
                String.class
            )
        );
        assertEquals(
            oldCleanup,
            db.queryForObject(
                "SELECT first_cleaned_at FROM document_export_artifact WHERE token=?",
                java.sql.Timestamp.class,
                oldToken
            )
        );
        assertNull(
            db.queryForObject(
                "SELECT first_cleaned_at FROM document_export_artifact WHERE token=?",
                java.sql.Timestamp.class,
                currentToken
            )
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM knowledge_vector_artifact WHERE tenant_id=1",
                Integer.class
            )
        );
        assertEquals(
            1,
            db.queryForObject(
                "SELECT COUNT(*) FROM knowledge_vector_artifact WHERE tenant_id=1 AND knowledge_document_id=11 AND file_version_id=8 AND index_version=2 AND collection_name='lexflow_upgrade_synthetic_known' AND retired_at IS NULL",
                Integer.class
            )
        );
        assertEquals(
            0,
            db.queryForObject(
                "SELECT COUNT(*) FROM knowledge_vector_artifact WHERE knowledge_document_id=14",
                Integer.class
            )
        );
        assertEquals(
            0,
            db.queryForObject("SELECT COUNT(*) FROM file_cleanup_tombstone", Integer.class)
        );
        assertEquals(
            0,
            db.queryForObject("SELECT COUNT(*) FROM export_cleanup_tombstone", Integer.class)
        );
    }
}
