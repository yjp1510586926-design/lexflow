-- MySQL 8.4 / InnoDB. All dates are UTC. No sample users or production credentials.
CREATE TABLE tenant (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 name VARCHAR(200) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_tenant_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_user (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 username VARCHAR(100) NOT NULL UNIQUE,
 password_hash VARCHAR(255) NOT NULL,
 display_name VARCHAR(100) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 session_version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE tenant_user (
 tenant_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 PRIMARY KEY (tenant_id,user_id),
 FOREIGN KEY (tenant_id) REFERENCES tenant(id),
 FOREIGN KEY (user_id) REFERENCES sys_user(id),
 CONSTRAINT ck_membership_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_role (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 code VARCHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL,
 UNIQUE (tenant_id,id), UNIQUE (tenant_id,code),
 FOREIGN KEY (tenant_id) REFERENCES tenant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE permission (
 code VARCHAR(64) PRIMARY KEY,
 name VARCHAR(100) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE user_role (
 tenant_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 role_id BIGINT NOT NULL,
 PRIMARY KEY (tenant_id,user_id,role_id),
 FOREIGN KEY (tenant_id,user_id) REFERENCES tenant_user(tenant_id,user_id),
 FOREIGN KEY (tenant_id,role_id) REFERENCES sys_role(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE role_permission (
 tenant_id BIGINT NOT NULL,
 role_id BIGINT NOT NULL,
 permission_code VARCHAR(64) NOT NULL,
 PRIMARY KEY (tenant_id,role_id,permission_code),
 FOREIGN KEY (tenant_id,role_id) REFERENCES sys_role(tenant_id,id),
 FOREIGN KEY (permission_code) REFERENCES permission(code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE case_info (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 case_no VARCHAR(100) NOT NULL,
 title VARCHAR(200) NOT NULL,
 case_type VARCHAR(64) NOT NULL,
 description TEXT,
 owner_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
 filed_on DATE,
 archived_at TIMESTAMP(6) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE (tenant_id,id), UNIQUE (tenant_id,case_no),
 FOREIGN KEY (tenant_id,owner_id) REFERENCES tenant_user(tenant_id,user_id),
 CONSTRAINT ck_case_status CHECK (status IN ('DRAFT','ACTIVE','ARCHIVED')),
 INDEX ix_case_list (tenant_id,status,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE case_member (
 tenant_id BIGINT NOT NULL,
 case_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 member_role VARCHAR(16) NOT NULL,
 PRIMARY KEY (tenant_id,case_id,user_id),
 FOREIGN KEY (tenant_id,case_id) REFERENCES case_info(tenant_id,id),
 FOREIGN KEY (tenant_id,user_id) REFERENCES tenant_user(tenant_id,user_id),
 CONSTRAINT ck_case_member_role CHECK (member_role IN ('OWNER','EDITOR','READER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE file_object (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 case_id BIGINT NOT NULL,
 display_name VARCHAR(255) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'UPLOADING',
 created_by BIGINT NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 deleted_at TIMESTAMP(6) NULL,
 UNIQUE (tenant_id,id), UNIQUE (tenant_id,case_id,id),
 FOREIGN KEY (tenant_id,case_id) REFERENCES case_info(tenant_id,id),
 FOREIGN KEY (tenant_id,created_by) REFERENCES tenant_user(tenant_id,user_id),
 CONSTRAINT ck_file_status CHECK (status IN ('UPLOADING','AVAILABLE','FAILED','DELETED')),
 INDEX ix_file_list (tenant_id,case_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE file_version (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NOT NULL,
 file_id BIGINT NOT NULL,
 version_no INT NOT NULL,
 object_key VARCHAR(512) NOT NULL UNIQUE,
 sha256 CHAR(64) NOT NULL,
 size_bytes BIGINT NOT NULL,
 mime_type VARCHAR(100) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE (tenant_id,id), UNIQUE (tenant_id,file_id,version_no),
 FOREIGN KEY (tenant_id,file_id) REFERENCES file_object(tenant_id,id),
 CONSTRAINT ck_file_size CHECK (size_bytes > 0 AND size_bytes <= 52428800)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE audit_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 tenant_id BIGINT NULL,
 actor_id BIGINT NULL,
 action VARCHAR(64) NOT NULL,
 object_type VARCHAR(64),
 object_id VARCHAR(64),
 result VARCHAR(16) NOT NULL,
 trace_id VARCHAR(64) NOT NULL,
 detail_json JSON,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_audit_tenant (tenant_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
