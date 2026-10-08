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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ResourceMigrationTest {
    @BeforeAll static void guard() throws Exception { FoundationMigrationTest.guardedPrivateConnection(); }
    private void fixture(String name, BiConsumer<JdbcTemplate,W03VersionRepository> check) {
        var jdbc=FoundationMigrationTest.fresh(name);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(EnterpriseJpaConfiguration.class,EnterpriseMigrationEvolutionTest.RepositoryConfiguration.class)
                .withBean(DataSource.class,jdbc::getDataSource)
                .withBean(PersistenceManagedTypes.class,()->PersistenceManagedTypes.of(DeStandaloneVersion.class.getName()))
                .withBean(EnterpriseFoundationSqlBlock.class,()->new EnterpriseFoundationSqlBlock(jdbc))
                .withBean(EnterpriseAuditSqlBlock.class,()->new EnterpriseAuditSqlBlock(jdbc))
                .withBean(EnterpriseAuthoritySqlBlock.class,()->new EnterpriseAuthoritySqlBlock(jdbc))
                .withBean(EnterpriseCredentialSqlBlock.class,()->new EnterpriseCredentialSqlBlock(jdbc))
                .withBean(EnterpriseResourceSqlBlock.class,()->new EnterpriseResourceSqlBlock(jdbc))
                .withPropertyValues("enterprise.foundation.enabled=true","spring.jpa.hibernate.ddl-auto=update")
                .run(c->{assertThat(c).hasNotFailed();var previous=SpringContextUtil.getApplicationContext();
                    try{new SpringContextUtil().setApplicationContext(c.getSourceApplicationContext());check.accept(jdbc,c.getBean(W03VersionRepository.class));}
                    finally{new SpringContextUtil().setApplicationContext(previous);}});
    }
    private void run(W03VersionRepository versions) {
        var listener=new InitSqlListener();ReflectionTestUtils.setField(listener,"deStandaloneVersionRepository",versions);listener.run(null);
    }
    @Test void exactFiveStepEmptyPlanAndRepeatProvideNoResources() {
        fixture("resourceledger",(jdbc,versions)->{run(versions);run(versions);new FoundationSchemaVerifier(jdbc).run(null);
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.5","4.4","4.3","4.2","4.1");
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getSuccess).containsOnly(true);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_resource",Long.class)).isZero();});
    }
    @Test void v44DataSurvivesResourceUpgradeWithoutHistoryRewrite() {
        var jdbc=FoundationMigrationTest.fresh("resourceupgrade");new EnterpriseFoundationSqlBlock(jdbc).execute();new EnterpriseAuditSqlBlock(jdbc).execute();
        new EnterpriseAuthoritySqlBlock(jdbc).execute();new EnterpriseCredentialSqlBlock(jdbc).execute();
        jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES(1,'retained','Retained')");
        new EnterpriseResourceSqlBlock(jdbc).execute();new EnterpriseResourceSqlBlock(jdbc).execute();new FoundationSchemaVerifier(jdbc).run(null);
        assertThat(jdbc.queryForObject("SELECT username FROM de_ent_user WHERE id=1",String.class)).isEqualTo("retained");
    }
    @Test void committedResourceDdlFailureRetainsFailedLedgerAndSafelyRetries() {
        fixture("resourceretry",(jdbc,versions)->{var interrupted=spy(jdbc);var fail=new AtomicBoolean(true);
            doAnswer(call->{String sql=call.getArgument(0);jdbc.execute(sql);
                if(sql.startsWith("CREATE TABLE `de_ent_resource`")&&fail.getAndSet(false))throw new IllegalStateException("Synthetic committed resource DDL failure");return null;}).when(interrupted).execute(anyString());
            var current=SpringContextUtil.getApplicationContext();
            try(var ctx=new org.springframework.context.support.GenericApplicationContext()) {
                ctx.registerBean(EnterpriseFoundationSqlBlock.class,()->new EnterpriseFoundationSqlBlock(jdbc));
                ctx.registerBean(EnterpriseAuditSqlBlock.class,()->new EnterpriseAuditSqlBlock(jdbc));
                ctx.registerBean(EnterpriseAuthoritySqlBlock.class,()->new EnterpriseAuthoritySqlBlock(jdbc));
                ctx.registerBean(EnterpriseCredentialSqlBlock.class,()->new EnterpriseCredentialSqlBlock(jdbc));
                ctx.registerBean(EnterpriseResourceSqlBlock.class,()->new EnterpriseResourceSqlBlock(interrupted));ctx.refresh();new SpringContextUtil().setApplicationContext(ctx);
                assertThatThrownBy(()->run(versions)).hasMessageContaining("4.5");
            }finally{new SpringContextUtil().setApplicationContext(current);}
            run(versions);new FoundationSchemaVerifier(jdbc).run(null);
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.5","4.5","4.4","4.3","4.2","4.1");
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getSuccess).containsExactly(true,false,true,true,true,true);
        });
    }
    @Test void ownershipConstraintsRejectBothGroupsAndPayloadClaims() {
        var jdbc=FoundationMigrationTest.migrated("resourceconstraints");
        jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES(1,'synthetic','Synthetic')");
        jdbc.update("INSERT INTO de_ent_tenant(id,code,name) VALUES(10,'A','A'),(20,'B','B')");
        jdbc.update("INSERT INTO de_ent_org(id,tenant_id,kind,name,school_code) VALUES(100,10,'SCHOOL','A','A'),(200,20,'SCHOOL','B','B')");
        jdbc.update("INSERT INTO de_ent_resource(id,tenant_id,resource_type) VALUES(101,10,'DASHBOARD'),(201,20,'DASHBOARD')");
        for(long tenant:new long[]{10,20}){
            long foreign=tenant==10?200:100;
            foreignKeyFailure(()->jdbc.update("INSERT INTO de_ent_resource(id,tenant_id,resource_type,resource_kind,school_id) VALUES(999,?,'DASHBOARD','SCHOOL_COPY',?)",tenant,foreign));
            foreignKeyFailure(()->jdbc.update("INSERT INTO de_ent_resource(id,tenant_id,resource_type,parent_resource_id) VALUES(999,?,'CHART',?)",tenant,foreign+1));
        }
        assertThatThrownBy(()->jdbc.update("INSERT INTO de_ent_resource(id,tenant_id,resource_type,payload_policy) VALUES(999,10,'DASHBOARD','SYNTHETIC_ONLY')")).isInstanceOf(RuntimeException.class).hasStackTraceContaining("ck_resource_payload");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_resource",Long.class)).isEqualTo(2);
        jdbc.execute("ALTER TABLE de_ent_resource ALTER CHECK ck_resource_payload NOT ENFORCED");
        assertThatThrownBy(()->new EnterpriseResourceSqlBlock(jdbc).execute()).isInstanceOf(IllegalStateException.class);
    }
    private static void foreignKeyFailure(Runnable command) {
        Throwable failure=catchThrowable(command::run);
        assertThat(failure).isNotNull();
        while(failure.getCause()!=null)failure=failure.getCause();
        assertThat(failure).isInstanceOf(java.sql.SQLException.class);
        var sql=(java.sql.SQLException)failure;
        assertThat(sql.getSQLState()).isEqualTo("23000");
        assertThat(sql.getErrorCode()).isIn(1216,1452);
    }
}
