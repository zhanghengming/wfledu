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

/** Real production ledger/aspect/listener; the 4.3 history remains testable after later targets. */
class AuthorityMigrationTest {
    @BeforeAll static void guard() throws Exception { FoundationMigrationTest.guardedPrivateConnection(); }
    private void fixture(String name,BiConsumer<JdbcTemplate,W03VersionRepository> check) {
        var jdbc=FoundationMigrationTest.fresh(name);
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(EnterpriseJpaConfiguration.class,EnterpriseMigrationEvolutionTest.RepositoryConfiguration.class)
                .withBean(DataSource.class,jdbc::getDataSource)
                .withBean(PersistenceManagedTypes.class,()->PersistenceManagedTypes.of(DeStandaloneVersion.class.getName()))
                .withBean(EnterpriseFoundationSqlBlock.class,()->new EnterpriseFoundationSqlBlock(jdbc))
                .withBean(EnterpriseAuditSqlBlock.class,()->new EnterpriseAuditSqlBlock(jdbc))
                .withBean(EnterpriseAuthoritySqlBlock.class,()->new EnterpriseAuthoritySqlBlock(jdbc))
                .withPropertyValues("enterprise.foundation.enabled=true","spring.jpa.hibernate.ddl-auto=update")
                .run(c->{assertThat(c).hasNotFailed();var previous=SpringContextUtil.getApplicationContext();
                    try{new SpringContextUtil().setApplicationContext(c.getSourceApplicationContext());check.accept(jdbc,c.getBean(W03VersionRepository.class));}
                    finally{new SpringContextUtil().setApplicationContext(previous);}});
    }
    private void run(W03VersionRepository versions) {
        var listener=new InitSqlListener();ReflectionTestUtils.setField(listener,"deStandaloneVersionRepository",versions);listener.run(null);
    }
    @Test void formalThreeStepEmptyPlanKeepsSuccessfulHistoryOnRestart() {
        fixture("authorityledger",(jdbc,versions)->{run(versions);run(versions);new FoundationSchemaVerifier(jdbc,FoundationSchemaV43.TABLES).run(null);
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.3","4.2","4.1");
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getSuccess).containsOnly(true);
            for(var table:FoundationSchemaV43.TABLES) assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `"+table.name()+"`",Long.class)).isZero();});
    }
    @Test void committedFirstAuthorityTableFailureRetainsLedgerAndRetries() {
        fixture("authorityretry",(jdbc,versions)->{var current=SpringContextUtil.getApplicationContext();var interrupted=spy(jdbc);var fail=new AtomicBoolean(true);
            doAnswer(call->{String sql=call.getArgument(0);jdbc.execute(sql);
                if(sql.startsWith("CREATE TABLE `de_ent_org_member`")&&fail.getAndSet(false))throw new IllegalStateException("Synthetic committed authority DDL failure");return null;}).when(interrupted).execute(anyString());
            try(var ctx=new GenericApplicationContext()) {
                ctx.registerBean(EnterpriseFoundationSqlBlock.class,()->new EnterpriseFoundationSqlBlock(jdbc));
                ctx.registerBean(EnterpriseAuditSqlBlock.class,()->new EnterpriseAuditSqlBlock(jdbc));
                ctx.registerBean(EnterpriseAuthoritySqlBlock.class,()->new EnterpriseAuthoritySqlBlock(interrupted));ctx.refresh();new SpringContextUtil().setApplicationContext(ctx);
                assertThatThrownBy(()->run(versions)).hasMessageContaining("4.3");
            }finally{new SpringContextUtil().setApplicationContext(current);}
            FoundationSchema.verify(jdbc,FoundationSchemaV43.ADDITIONS.getFirst());run(versions);run(versions);
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.3","4.3","4.2","4.1");
            assertThat(versions.findRecords()).extracting(DeStandaloneVersion::getSuccess).containsExactly(true,false,true,true);
            new FoundationSchemaVerifier(jdbc,FoundationSchemaV43.TABLES).run(null);});
    }
    @Test void v42RowsSurviveRealListenerUpgradeWithoutReexecutingOldSteps() {
        fixture("authorityrowupgrade",(jdbc,versions)->{var current=SpringContextUtil.getApplicationContext();
            try(var ctx=new GenericApplicationContext()) {
                ctx.registerBean(EnterpriseFoundationSqlBlock.class,()->new EnterpriseFoundationSqlBlock(jdbc));
                ctx.registerBean(EnterpriseAuditSqlBlock.class,()->new EnterpriseAuditSqlBlock(jdbc));ctx.refresh();new SpringContextUtil().setApplicationContext(ctx);run(versions);
            }finally{new SpringContextUtil().setApplicationContext(current);}
            jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES(1,'retained','Retained')");run(versions);
            assertThat(jdbc.queryForObject("SELECT username FROM de_ent_user WHERE id=1",String.class)).isEqualTo("retained");assertThat(versions.findRecords()).hasSize(3);});
    }
    @Test void authorityDriftRefusesBeforeCreatingRemainingTables() {
        var jdbc=FoundationMigrationTest.fresh("authoritydrift");new EnterpriseFoundationSqlBlock(jdbc).execute();new EnterpriseAuditSqlBlock(jdbc).execute();
        var first=FoundationSchemaV43.ADDITIONS.getFirst();jdbc.execute(first.ddl());jdbc.execute("ALTER TABLE de_ent_org_member ALTER CHECK ck_org_member_status NOT ENFORCED");
        var before=jdbc.queryForMap("SHOW CREATE TABLE de_ent_org_member");assertThatThrownBy(()->new EnterpriseAuthoritySqlBlock(jdbc).execute()).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForMap("SHOW CREATE TABLE de_ent_org_member")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_role'",Integer.class)).isZero();
    }
    @Test void sixTableDescriptorsRemainImmutableAndFullyCommented() {
        assertThat(FoundationSchemaV43.ADDITIONS).hasSize(6);assertThat(FoundationSchemaV43.TABLES).hasSize(11);
        assertThatThrownBy(()->FoundationSchemaV43.ADDITIONS.clear()).isInstanceOf(UnsupportedOperationException.class);
        for(var table:FoundationSchemaV43.ADDITIONS) {assertThat(table.comment()).isNotBlank();assertThat(table.columns()).allSatisfy(c->assertThat(c.comment()).isNotBlank());
            assertThatThrownBy(()->table.checks().clear()).isInstanceOf(UnsupportedOperationException.class);}
    }
    @Test void unknownSubjectTypeAndInvalidCapabilityCannotGrantAuthority() {
        var jdbc=FoundationMigrationTest.migrated("authoritybranch");jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES(1,'one','One')");
        jdbc.update("INSERT INTO de_ent_tenant(id,code,name) VALUES(10,'A','A')");jdbc.update("INSERT INTO de_ent_tenant_member(id,tenant_id,user_id) VALUES(100,10,1)");
        assertThatThrownBy(()->jdbc.update("INSERT INTO de_ent_subject(id,tenant_id,subject_type,member_id) VALUES(120,10,'ROOT',100)"))
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,e->assertThat(((java.sql.SQLException)e.getRootCause()).getErrorCode()).isEqualTo(3819));
        jdbc.update("INSERT INTO de_ent_subject(id,tenant_id,subject_type,member_id) VALUES(120,10,'USER',100)");
        assertThatThrownBy(()->jdbc.update("INSERT INTO de_ent_admin_grant(id,tenant_id,subject_id,capability,effect,status) VALUES(130,10,120,'GROUP_READ_ALL','ALLOW','ACTIVE')"))
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,e->assertThat(((java.sql.SQLException)e.getRootCause()).getErrorCode()).isEqualTo(3819));
    }
}
