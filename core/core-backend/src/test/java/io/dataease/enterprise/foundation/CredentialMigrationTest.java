package io.dataease.enterprise.foundation;

import io.dataease.dao.auto.entity.DeStandaloneVersion;
import io.dataease.extensions.datasource.utils.SpringContextUtil;
import io.dataease.listener.InitSqlListener;
import io.dataease.migrationfixture.W03VersionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CredentialMigrationTest {
    @BeforeAll static void guard() throws Exception { FoundationMigrationTest.guardedPrivateConnection(); }
    private void fixture(String name,BiConsumer<JdbcTemplate,W03VersionRepository> check) {
        var jdbc=FoundationMigrationTest.fresh(name);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(EnterpriseJpaConfiguration.class,EnterpriseMigrationEvolutionTest.RepositoryConfiguration.class,ManagementSessionTest.Scan.class)
                .withBean(DataSource.class,jdbc::getDataSource)
                .withBean(PersistenceManagedTypes.class,()->PersistenceManagedTypes.of(DeStandaloneVersion.class.getName(),
                        io.dataease.enterprise.identity.persistence.EnterpriseUser.class.getName(),
                        io.dataease.enterprise.identity.persistence.EnterpriseUserCredential.class.getName(),
                        io.dataease.enterprise.identity.persistence.EnterprisePlatformQualification.class.getName(),
                        io.dataease.enterprise.identity.persistence.EnterpriseLoginSession.class.getName(),
                        io.dataease.enterprise.identity.persistence.EnterpriseTenant.class.getName(),
                        io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent.class.getName()))
                .withBean(EnterpriseFoundationSqlBlock.class,()->new EnterpriseFoundationSqlBlock(jdbc))
                .withBean(EnterpriseAuditSqlBlock.class,()->new EnterpriseAuditSqlBlock(jdbc))
                .withBean(EnterpriseAuthoritySqlBlock.class,()->new EnterpriseAuthoritySqlBlock(jdbc))
                .withBean(EnterpriseCredentialSqlBlock.class,()->new EnterpriseCredentialSqlBlock(jdbc))
                .withPropertyValues("enterprise.foundation.enabled=true","spring.jpa.hibernate.ddl-auto=update","dataease.machine-id=30")
                .run(c->{assertThat(c).hasNotFailed();var previous=SpringContextUtil.getApplicationContext();
                    try{new SpringContextUtil().setApplicationContext(c.getSourceApplicationContext());check.accept(jdbc,c.getBean(W03VersionRepository.class));}
                    finally{new SpringContextUtil().setApplicationContext(previous);}});
    }
    private void run(W03VersionRepository versions) {
        var listener=new InitSqlListener();ReflectionTestUtils.setField(listener,"deStandaloneVersionRepository",versions);listener.run(null);
    }
    @Test void formalFourStepEmptyPlanRetainsHistoryAndProvidesNoCredentials() {
        fixture("credentialledger",(jdbc,versions)->{run(versions);run(versions);new FoundationSchemaVerifier(jdbc,FoundationSchemaV44.TABLES).run(null);
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.4","4.3","4.2","4.1");
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getSuccess).containsOnly(true);
            for(var table:FoundationSchemaV44.TABLES) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `"+table.name()+"`",Long.class)).isZero();});
    }
    @Test void committedCredentialDdlFailureRetainsFailedLedgerAndRetries() {
        fixture("credentialretry",(jdbc,versions)->{var current=SpringContextUtil.getApplicationContext();var interrupted=spy(jdbc);var fail=new AtomicBoolean(true);
            doAnswer(call->{String sql=call.getArgument(0);jdbc.execute(sql);
                if(sql.startsWith("CREATE TABLE `de_ent_user_credential`")&&fail.getAndSet(false))throw new IllegalStateException("Synthetic committed credential DDL failure");return null;}).when(interrupted).execute(anyString());
            try(var ctx=new GenericApplicationContext()) {
                ctx.registerBean(EnterpriseFoundationSqlBlock.class,()->new EnterpriseFoundationSqlBlock(jdbc));
                ctx.registerBean(EnterpriseAuditSqlBlock.class,()->new EnterpriseAuditSqlBlock(jdbc));
                ctx.registerBean(EnterpriseAuthoritySqlBlock.class,()->new EnterpriseAuthoritySqlBlock(jdbc));
                ctx.registerBean(EnterpriseCredentialSqlBlock.class,()->new EnterpriseCredentialSqlBlock(interrupted));ctx.refresh();new SpringContextUtil().setApplicationContext(ctx);
                assertThatThrownBy(()->run(versions)).hasMessageContaining("4.4");
            }finally{new SpringContextUtil().setApplicationContext(current);}
            FoundationSchema.verify(jdbc,FoundationSchemaV44.ADDITIONS.getFirst());run(versions);run(versions);
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.4","4.4","4.3","4.2","4.1");
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getSuccess).containsExactly(true,false,true,true,true);
            new FoundationSchemaVerifier(jdbc,FoundationSchemaV44.TABLES).run(null);});
    }
    @Test void v43RowsSurviveListenerUpgradeWithoutOldStepsRepeating() {
        fixture("credentialupgrade",(jdbc,versions)->{var current=SpringContextUtil.getApplicationContext();
            try(var ctx=new GenericApplicationContext()) {
                ctx.registerBean(EnterpriseFoundationSqlBlock.class,()->new EnterpriseFoundationSqlBlock(jdbc));
                ctx.registerBean(EnterpriseAuditSqlBlock.class,()->new EnterpriseAuditSqlBlock(jdbc));
                ctx.registerBean(EnterpriseAuthoritySqlBlock.class,()->new EnterpriseAuthoritySqlBlock(jdbc));ctx.refresh();new SpringContextUtil().setApplicationContext(ctx);run(versions);
            }finally{new SpringContextUtil().setApplicationContext(current);}
            jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES(1,'retained','Retained')");run(versions);
            assertThat(jdbc.queryForObject("SELECT username FROM de_ent_user WHERE id=1",String.class)).isEqualTo("retained");assertThat(versions.findRecords()).hasSize(4);});
    }
    @Test void credentialDriftRefusesBeforeCreatingRemainingTables() {
        var jdbc=FoundationMigrationTest.fresh("credentialdrift");new EnterpriseFoundationSqlBlock(jdbc).execute();new EnterpriseAuditSqlBlock(jdbc).execute();new EnterpriseAuthoritySqlBlock(jdbc).execute();
        var first=FoundationSchemaV44.ADDITIONS.getFirst();jdbc.execute(first.ddl());
        jdbc.execute("ALTER TABLE de_ent_user_credential ALTER CHECK ck_credential_parameters NOT ENFORCED");
        var before=jdbc.queryForMap("SHOW CREATE TABLE de_ent_user_credential");assertThatThrownBy(()->new EnterpriseCredentialSqlBlock(jdbc).execute()).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForMap("SHOW CREATE TABLE de_ent_user_credential")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_platform_qualification'",Integer.class)).isZero();
    }
    private io.dataease.enterprise.identity.manage.PlatformInitialization initializer(java.time.Clock clock) {
        var context=SpringContextUtil.getApplicationContext();
        return new io.dataease.enterprise.identity.manage.PlatformInitialization(context.getBean(jakarta.persistence.EntityManagerFactory.class),
                context.getBean(org.springframework.orm.jpa.JpaTransactionManager.class),new io.dataease.enterprise.identity.manage.PasswordCodec(),clock);
    }
    @Test void explicitInitializationIsAtomicRestrictedAndCannotRepeat() {
        fixture("credentialinitialize",(jdbc,versions)->{run(versions);char[] password="SyntheticPassword-123".toCharArray();
            long user=initializer(java.time.Clock.systemUTC()).initialize("operator","Operator",password,java.util.Set.of("PLATFORM_OPERATE","GROUP_READ_ALL"));
            assertThat(password).containsOnly('\0');assertThat(user).isPositive();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_user",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT must_reset+0 FROM de_ent_user_credential",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_platform_qualification WHERE status='ACTIVE'",Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_audit_event WHERE event_type='PLATFORM_INITIALIZED' AND actor_kind='SYSTEM'",Long.class)).isEqualTo(1);
            assertThatThrownBy(()->initializer(java.time.Clock.systemUTC()).initialize("another","Another","SyntheticPassword-123".toCharArray(),java.util.Set.of("PLATFORM_OPERATE")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("empty identity state");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_user",Long.class)).isEqualTo(1);});
    }
    @Test void failedInitializationRollsBackAccountAndQualifications() {
        fixture("credentialinitrollback",(jdbc,versions)->{run(versions);
            assertThatThrownBy(()->initializer(java.time.Clock.fixed(java.time.Instant.parse("+10000-01-01T00:00:00Z"),java.time.ZoneOffset.UTC))
                    .initialize("operator","Operator","SyntheticPassword-123".toCharArray(),java.util.Set.of("PLATFORM_OPERATE"))).isInstanceOf(RuntimeException.class);
            for(String table:new String[]{"user","user_credential","platform_qualification","login_session","audit_event"})
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_"+table,Long.class)).isZero();});
    }
    @Test void privateInputRejectsPermissionsDuplicateKeysAndTrailingJson() {
        fixture("credentialprivate",(jdbc,versions)->{run(versions);
            try {
                var path=java.nio.file.Files.createTempFile("w03-private-input-",".json");
                try {
                    java.nio.file.Files.writeString(path,"{\"username\":\"operator\",\"displayName\":\"Operator\",\"password\":\"SyntheticPassword-123\",\"qualifications\":[\"PLATFORM_OPERATE\"]}");
                    java.nio.file.Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
                    assertThatThrownBy(()->io.dataease.enterprise.bootstrap.PrivateInitializationInput.initialize(path,initializer(java.time.Clock.systemUTC())))
                            .isInstanceOf(IllegalStateException.class).hasMessageContaining("owner-only");
                    java.nio.file.Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                    String valid=java.nio.file.Files.readString(path);
                    java.nio.file.Files.writeString(path,valid+" {}");assertThatThrownBy(()->io.dataease.enterprise.bootstrap.PrivateInitializationInput.initialize(path,initializer(java.time.Clock.systemUTC())))
                            .isInstanceOf(IllegalStateException.class).hasMessage("Invalid initialization JSON");
                    java.nio.file.Files.writeString(path,valid.replace("\"username\":\"operator\"","\"username\":\"operator\",\"username\":\"operator\""));
                    assertThatThrownBy(()->io.dataease.enterprise.bootstrap.PrivateInitializationInput.initialize(path,initializer(java.time.Clock.systemUTC())))
                            .isInstanceOf(IllegalStateException.class).hasMessage("Invalid initialization JSON");
                    java.nio.file.Files.writeString(path,valid);io.dataease.enterprise.bootstrap.PrivateInitializationInput.initialize(path,initializer(java.time.Clock.systemUTC()));
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_user",Long.class)).isEqualTo(1);
                }finally{java.nio.file.Files.delete(path);}
            }catch(java.io.IOException failure){throw new AssertionError("Private input verification failed",failure);}
        });
    }
}
