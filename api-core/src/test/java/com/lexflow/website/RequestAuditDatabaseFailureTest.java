package com.lexflow.website;

import static org.junit.jupiter.api.Assertions.*;

import com.lexflow.service.audit.AuditOfflineBuffer;
import com.lexflow.service.audit.AuditOperationalMetrics;
import com.lexflow.service.audit.RequestAuditDatabaseWriter;
import com.lexflow.service.audit.RequestAuditWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class RequestAuditDatabaseFailureTest {

    @TempDir
    Path directory;

    @Test
    void transactionBeginConnectionFailureIsCaughtOutsideTheProxyAndDurablyBuffered()
        throws Exception {
        // Owned local socket deliberately never speaks MySQL. No external or shared database is contacted.
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var source = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:" +
                    socket.getLocalPort() +
                    "/synthetic_audit_only?connectTimeout=1000&socketTimeout=1000",
                "synthetic",
                "synthetic"
            );
            var proxy = new ProxyFactory(new RequestAuditDatabaseWriter(new JdbcTemplate(source)));
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(
                new TransactionInterceptor(
                    new DataSourceTransactionManager(source),
                    new AnnotationTransactionAttributeSource()
                )
            );
            var database = (RequestAuditDatabaseWriter) proxy.getProxy();
            var metrics = new AuditOperationalMetrics();
            var buffer = new AuditOfflineBuffer(
                directory.resolve("buffer").toString(),
                10,
                81920,
                metrics
            );
            var writer = new RequestAuditWriter(database, buffer, metrics);
            assertEquals(
                RequestAuditWriter.Outcome.BUFFERED,
                writer.record(
                    null,
                    "request:failure",
                    503,
                    "GET",
                    "UNMATCHED",
                    null,
                    UUID.randomUUID().toString()
                )
            );
            assertEquals(1, buffer.snapshot().records());
            assertEquals(1, buffer.batch(10).size());
            assertEquals(1, metrics.count(AuditOperationalMetrics.Signal.BUFFERED));
            assertEquals(0, metrics.count(AuditOperationalMetrics.Signal.COMMITTED));
        }
    }
}
