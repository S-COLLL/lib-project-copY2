package com.smartlib.config;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * MySQL connection pool from the MYSQL_* settings (the same variables the Node.js version used).
 * MYSQL_SSL_CA may hold the CA certificate itself (PEM text, "\n" escapes allowed) or a path to the
 * .pem file; the connection is then encrypted and the server certificate verified against it.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(Environment env) throws Exception {
        String[] target = hostAndPort(setting(env, "MYSQL_HOST", ""), setting(env, "MYSQL_PORT", ""));
        String host = target[0];
        String port = target[1];
        String database = setting(env, "MYSQL_DATABASE", "library_app");
        String poolDigits = setting(env, "MYSQL_POOL_SIZE", "10").replaceAll("\\D", "");
        int poolSize = poolDigits.isEmpty() ? 10 : Integer.parseInt(poolDigits);

        StringBuilder url = new StringBuilder("jdbc:mysql://").append(host).append(':').append(port).append('/').append(database)
            .append("?tinyInt1isBit=false&zeroDateTimeBehavior=CONVERT_TO_NULL&characterEncoding=UTF-8");
        String ca = env.getProperty("MYSQL_SSL_CA", "");
        if (!ca.isBlank()) {
            String password = UUID.randomUUID().toString();
            Path trustStore = trustStore(ca, password);
            url.append("&sslMode=VERIFY_CA")
                .append("&trustCertificateKeyStoreUrl=").append(trustStore.toUri())
                .append("&trustCertificateKeyStoreType=PKCS12")
                .append("&trustCertificateKeyStorePassword=").append(password);
        } else if ("true".equals(env.getProperty("MYSQL_SSL"))) {
            url.append("&sslMode=VERIFY_IDENTITY");
        } else {
            url.append("&allowPublicKeyRetrieval=true");
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url.toString());
        config.setUsername(setting(env, "MYSQL_USER", "root"));
        config.setPassword(setting(env, "MYSQL_PASSWORD", ""));
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(Math.min(2, poolSize));
        config.setIdleTimeout(60_000);
        config.setKeepaliveTime(120_000);
        config.setConnectionTimeout(10_000);
        // Start even when the database is unreachable; requests then answer "database unavailable" until it is back.
        config.setInitializationFailTimeout(-1);
        config.setPoolName("smartlib");
        return new HikariDataSource(config);
    }

    private static final Pattern PORT = Pattern.compile("^\\d{1,5}$");
    private static final Pattern HOST_PORT = Pattern.compile("^(localhost|[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)(?::(\\d{1,5}))?");

    /**
     * The database host and port from MYSQL_HOST / MYSQL_PORT, tolerating values pasted into a hosting
     * dashboard the wrong way: "host:port", a whole service URI (mysql://user:pass@host:port/db), or
     * host and port swapped between the two settings.
     */
    static String[] hostAndPort(String rawHost, String rawPort) {
        String host = null;
        String port = null;
        if (PORT.matcher(rawPort).matches()) port = rawPort;
        else if (PORT.matcher(rawHost).matches()) port = rawHost;
        for (String v : new String[] {rawHost, rawPort}) {
            if (v.isEmpty() || PORT.matcher(v).matches()) continue;
            String s = v.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "").replaceFirst("^[^@/]*@", "");
            Matcher m = HOST_PORT.matcher(s);
            if (m.find()) {
                if (host == null) host = m.group(1);
                if (port == null && m.group(2) != null) port = m.group(2);
            }
        }
        return new String[] {host == null ? "localhost" : host, port == null ? "3306" : port};
    }

    private static String setting(Environment env, String key, String fallback) {
        String v = env.getProperty(key, "").strip();
        return v.isEmpty() ? fallback : v;
    }

    /** Writes the PEM certificate(s) into a temporary PKCS12 trust store for the MySQL driver. */
    private static Path trustStore(String ca, String password) throws Exception {
        String pem = ca.contains("-----BEGIN") ? ca.replace("\\n", "\n") : Files.readString(Path.of(ca));
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs = factory.generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, password.toCharArray());
        int i = 0;
        for (Certificate cert : certs) store.setCertificateEntry("mysql-ca-" + i++, cert);
        Path file = Files.createTempFile("smartlib-mysql-ca", ".p12");
        file.toFile().deleteOnExit();
        try (OutputStream out = Files.newOutputStream(file)) {
            store.store(out, password.toCharArray());
        }
        return file;
    }
}
