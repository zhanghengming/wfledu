package io.dataease.enterprise.foundation;

import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent;
import io.dataease.enterprise.context.AccessContextHolder;
import io.dataease.enterprise.identity.manage.ManagementSessionService;
import io.dataease.enterprise.identity.manage.PasswordCodec;
import io.dataease.enterprise.identity.persistence.EnterpriseUser;
import io.dataease.exception.DEException;
import io.dataease.utils.IDUtils;
import io.dataease.utils.SnowFlake;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

class ManagementSessionTest {
    @Configuration(proxyBeanMethods=false)
    @EntityScan(basePackageClasses={EnterpriseUser.class,EnterpriseAuditEvent.class})
    @Import({IDUtils.class,SnowFlake.class}) static class Scan { }
    @BeforeAll static void guard() throws Exception { FoundationMigrationTest.guardedPrivateConnection(); }
    private static final class Time extends Clock {
        private Instant value=Instant.parse("2026-10-08T00:00:00Z");
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return value;}
        void advance(long seconds){value=value.plusSeconds(seconds);}
    }
    private record Fixture(JdbcTemplate jdbc,ManagementSessionService service,Time clock) {
        ManagementSessionService.LoginResult login(){return service.login("synthetic", "SyntheticPassword-123".toCharArray());}
        long number(String sql){return jdbc.queryForObject(sql,Long.class);}
    }
    private void fixture(String name,boolean reset,Consumer<Fixture> check) {
        var jdbc=FoundationMigrationTest.fresh(name);
        new EnterpriseFoundationSqlBlock(jdbc).execute();
        new EnterpriseAuditSqlBlock(jdbc).execute();
        new EnterpriseAuthoritySqlBlock(jdbc).execute();
        new EnterpriseCredentialSqlBlock(jdbc).execute();
        var codec=new PasswordCodec();var time=new Time();
        jdbc.update("INSERT INTO de_ent_user(id,username,display_name,status) VALUES(1,'synthetic','Synthetic','ACTIVE')");
        jdbc.update("INSERT INTO de_ent_user_credential(id,user_id,encoded_hash,password_changed_at,must_reset) VALUES(2,1,?,'2026-10-08 00:00:00',?)",codec.encode("SyntheticPassword-123".toCharArray()),reset);
        jdbc.update("INSERT INTO de_ent_tenant(id,code,name,status) VALUES(10,'A','A','ACTIVE'),(20,'B','B','ACTIVE')");
        jdbc.update("INSERT INTO de_ent_tenant_member(id,tenant_id,user_id,status) VALUES(100,10,1,'ACTIVE')");
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(EnterpriseJpaConfiguration.class,Scan.class).withBean(DataSource.class,jdbc::getDataSource)
                .withPropertyValues("enterprise.foundation.enabled=true","spring.jpa.hibernate.ddl-auto=update","dataease.machine-id=30")
                .run(c->{assertThat(c).hasNotFailed();var service=new ManagementSessionService(c.getBean(EntityManagerFactory.class),c.getBean(JpaTransactionManager.class),time,codec);
                    check.accept(new Fixture(jdbc,service,time));});
        new FoundationSchemaVerifier(jdbc,FoundationSchemaV44.TABLES).run(null);
        assertThat(AccessContextHolder.current()).isEmpty();
    }
    private static void rejected(Runnable call,int code){assertThatThrownBy(call::run).isInstanceOfSatisfying(DEException.class,e->assertThat(e.getCode()).isEqualTo(code));}

    @Test void loginPersistsOnlyDigestAndReturnsUnselectedRestrictedIdentity() {
        fixture("loginsecret",false,f->{char[] password="SyntheticPassword-123".toCharArray();var login=f.service.login("synthetic",password);
            assertThat(password).containsOnly('\0');assertThat(login.credential()).matches("[A-Za-z0-9_-]{43}");assertThat(login.toString()).doesNotContain(login.credential());
            assertThat(login.principal().tenantId()).isNull();assertThat(f.service.authenticate(login.credential())).isEqualTo(login.principal());
            assertThat(f.number("SELECT OCTET_LENGTH(token_hash) FROM de_ent_login_session")).isEqualTo(32);
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event WHERE event_type='LOGIN_SUCCEEDED'")).isEqualTo(1);
            rejected(()->f.service.access(login.principal()),70001);});
    }
    @Test void fiveFailuresLockAtomicallyAndExpiryRestartsCounter() {
        fixture("loginlock",false,f->{for(int i=0;i<5;i++)rejected(()->f.service.login("synthetic","WrongPassword-123".toCharArray()),20002);
            assertThat(f.number("SELECT failed_attempts FROM de_ent_user_credential")).isEqualTo(5);rejected(f::login,20002);
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_login_session")).isZero();f.clock.advance(901);f.login();
            assertThat(f.number("SELECT failed_attempts FROM de_ent_user_credential")).isZero();assertThat(f.jdbc.queryForObject("SELECT locked_until FROM de_ent_user_credential",Object.class)).isNull();});
    }
    @Test void concurrentWrongPasswordsAllCountTowardsLock() {
        fixture("loginconcurrent",false,f->{
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(5)) {
                var start=new java.util.concurrent.CountDownLatch(1);
                var calls=java.util.stream.IntStream.range(0,5).mapToObj(i->pool.submit(()->{start.await();
                    rejected(()->f.service.login("synthetic","WrongPassword-123".toCharArray()),20002);return true;})).toList();
                start.countDown();for(var call:calls)assertThat(call.get(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            } catch(Exception failure){throw new AssertionError("Concurrent login verification failed",failure);}
            assertThat(f.number("SELECT failed_attempts FROM de_ent_user_credential")).isEqualTo(5);
            rejected(f::login,20002);
        });
    }
    @Test void unknownAndDisabledIdentityUseSameLoginErrorWithoutSession() {
        fixture("loginunknown",false,f->{rejected(()->f.service.login("unknown","SyntheticPassword-123".toCharArray()),20002);
            f.jdbc.update("UPDATE de_ent_user SET status='DISABLED' WHERE id=1");rejected(f::login,20002);assertThat(f.number("SELECT COUNT(*) FROM de_ent_login_session")).isZero();});
    }
    @Test void memberSwitchRejectsOtherGroupAndStaleRevision() {
        fixture("loginswitch",false,f->{var login=f.login();rejected(()->f.service.switchTenant(login.principal(),20,1),70001);
            var selected=f.service.switchTenant(login.principal(),10,1);assertThat(selected.version()).isEqualTo(2);assertThat(f.service.access(selected).tenantId()).isEqualTo(10);
            rejected(()->f.service.switchTenant(selected,10,1),50002);rejected(()->f.service.access(login.principal()),70001);
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_audit_event WHERE event_type='CONTEXT_SWITCHED'")).isEqualTo(1);});
    }
    @Test void platformAllReadSwitchesWithoutMembershipButDoesNotBecomeOperate() {
        fixture("loginplatform",false,f->{f.jdbc.update("INSERT INTO de_ent_platform_qualification(id,user_id,qualification,status) VALUES(3,1,'GROUP_READ_ALL','ACTIVE')");
            var login=f.login();var switched=f.service.switchTenant(login.principal(),20,1);assertThat(f.service.access(switched).tenantId()).isEqualTo(20);
            assertThat(f.number("SELECT COUNT(*) FROM de_ent_platform_qualification WHERE qualification='PLATFORM_OPERATE'")).isZero();
            f.jdbc.update("UPDATE de_ent_platform_qualification SET status='DISABLED',version=version+1 WHERE id=3");rejected(()->f.service.authenticate(login.credential()),70001);});
    }
    @Test void forcedResetCannotSwitchAndPasswordChangeRevokesEveryOldSession() {
        fixture("loginreset",true,f->{var first=f.login();var second=f.login();assertThat(first.principal().mustReset()).isTrue();
            rejected(()->f.service.switchTenant(first.principal(),10,1),70001);
            char[] old="SyntheticPassword-123".toCharArray(),next="ReplacementPassword-123".toCharArray();f.service.changePassword(first.principal(),old,next);
            assertThat(old).containsOnly('\0');assertThat(next).containsOnly('\0');rejected(()->f.service.authenticate(first.credential()),20001);rejected(()->f.service.authenticate(second.credential()),20001);
            assertThat(f.number("SELECT identity_epoch FROM de_ent_user")).isEqualTo(2);assertThat(f.number("SELECT COUNT(*) FROM de_ent_login_session WHERE status='REVOKED'")).isEqualTo(2);
            rejected(f::login,20002);assertThat(f.service.login("synthetic","ReplacementPassword-123".toCharArray()).principal().mustReset()).isFalse();});
    }
    @Test void idleAndAbsoluteExpiryAndLogoutDenyFurtherUse() {
        fixture("loginexpiry",false,f->{var first=f.login();f.clock.advance(1800);rejected(()->f.service.authenticate(first.credential()),20001);
            var second=f.login();f.service.logout(second.principal());rejected(()->f.service.authenticate(second.credential()),20001);
            var third=f.login();f.clock.advance(8*3600);rejected(()->f.service.authenticate(third.credential()),20001);});
    }
    @Test void memberTenantAndIdentityRevocationApplyToExistingSessions() {
        fixture("loginrevoke",false,f->{var login=f.login();var selected=f.service.switchTenant(login.principal(),10,1);
            f.jdbc.update("UPDATE de_ent_tenant_member SET status='DISABLED' WHERE id=100");rejected(()->f.service.authenticate(login.credential()),70001);
            f.jdbc.update("UPDATE de_ent_tenant_member SET status='ACTIVE' WHERE id=100");f.jdbc.update("UPDATE de_ent_tenant SET status='DISABLED' WHERE id=10");rejected(()->f.service.access(selected),70001);
            f.jdbc.update("UPDATE de_ent_tenant SET status='ACTIVE' WHERE id=10");f.jdbc.update("UPDATE de_ent_user SET identity_epoch=identity_epoch+1 WHERE id=1");rejected(()->f.service.authenticate(login.credential()),20001);});
    }
    @Test void malformedTokensCannotSelectAnIdentity() {
        fixture("logintoken",false,f->{for(String token:new String[]{"1","Bearer admin","x".repeat(43),"A".repeat(42)+"B"})rejected(()->f.service.authenticate(token),20001);});
    }
    @Test void corruptStoredParametersFailClosedWithoutCreatingSession() {
        fixture("loginparameters",false,f->{f.jdbc.update("UPDATE de_ent_user_credential SET encoded_hash='PBKDF2_SHA256$1$bad$bad'");
            assertThatThrownBy(f::login).isInstanceOf(IllegalStateException.class);assertThat(f.number("SELECT COUNT(*) FROM de_ent_login_session")).isZero();});
    }
}
