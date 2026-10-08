package io.dataease.enterprise.foundation;

import io.dataease.enterprise.audit.manage.OrganizationAuditAppender;
import io.dataease.enterprise.audit.persistence.EnterpriseAuditEvent;
import io.dataease.enterprise.context.AccessContext;
import io.dataease.enterprise.context.AccessContextHolder;
import io.dataease.enterprise.identity.persistence.EnterpriseUser;
import io.dataease.enterprise.management.manage.ManagementAuthority;
import io.dataease.enterprise.management.persistence.EnterpriseSubject;
import io.dataease.enterprise.tenant.domain.OrganizationNode;
import io.dataease.enterprise.tenant.manage.OrganizationTransactionKernel;
import io.dataease.exception.DEException;
import io.dataease.utils.IDUtils;
import io.dataease.utils.SnowFlake;
import jakarta.persistence.EntityManager;
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
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;

class ManagementAuthorityTest {
    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = {EnterpriseUser.class, EnterpriseAuditEvent.class, EnterpriseSubject.class})
    @Import({IDUtils.class, SnowFlake.class})
    static class Scan { }
    @BeforeAll static void guard() throws Exception { FoundationMigrationTest.guardedPrivateConnection(); }

    private void fixture(String name, Consumer<Fixture> check) {
        var jdbc=FoundationMigrationTest.migrated(name);
        jdbc.update("INSERT INTO de_ent_user(id,username,display_name,status) VALUES(1,'synthetic','Synthetic','ACTIVE')");
        jdbc.update("INSERT INTO de_ent_tenant(id,code,name,status) VALUES(10,'A','A','ACTIVE'),(20,'B','B','ACTIVE')");
        jdbc.update("INSERT INTO de_ent_tenant_member(id,tenant_id,user_id,status) VALUES(100,10,1,'ACTIVE'),(200,20,1,'ACTIVE')");
        jdbc.update("INSERT INTO de_ent_org(id,tenant_id,kind,name,school_code,status) VALUES(101,10,'SCHOOL','School','A101','ACTIVE'),(201,20,'SCHOOL','School','B201','ACTIVE')");
        jdbc.update("INSERT INTO de_ent_role(id,tenant_id,code,name,status) VALUES(110,10,'administrator','Super Administrator','ACTIVE')");
        jdbc.update("INSERT INTO de_ent_subject(id,tenant_id,subject_type,member_id) VALUES(120,10,'USER',100)");
        jdbc.update("INSERT INTO de_ent_subject(id,tenant_id,subject_type,org_id) VALUES(121,10,'ORG',101)");
        jdbc.update("INSERT INTO de_ent_subject(id,tenant_id,subject_type,role_id) VALUES(122,10,'ROLE',110)");
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(EnterpriseJpaConfiguration.class, Scan.class).withBean(DataSource.class,jdbc::getDataSource)
                .withPropertyValues("enterprise.foundation.enabled=true","spring.jpa.hibernate.ddl-auto=update","dataease.machine-id=30")
                .run(c->{assertThat(c).hasNotFailed();check.accept(new Fixture(jdbc,c.getBean(EntityManagerFactory.class),c.getBean(JpaTransactionManager.class)));});
        new FoundationSchemaVerifier(jdbc).run(null);
        assertThat(AccessContextHolder.current()).isEmpty();
    }
    private record Fixture(JdbcTemplate jdbc, EntityManagerFactory factory, JpaTransactionManager manager) {
        void grant(long id,long subject,String effect) {
            jdbc.update("INSERT INTO de_ent_admin_grant(id,tenant_id,subject_id,capability,effect,status) VALUES(?,10,?,'MANAGE_ORGANIZATIONS',?,'ACTIVE')",id,subject,effect);
        }
        void org() { jdbc.update("INSERT INTO de_ent_org_member(id,tenant_id,member_id,org_id,status) VALUES(130,10,100,101,'ACTIVE')"); }
        void assignment() {
            jdbc.update("INSERT INTO de_ent_role_assignment(id,tenant_id,member_id,role_id,status) VALUES(140,10,100,110,'ACTIVE')");
            jdbc.update("INSERT INTO de_ent_assignment_school(id,tenant_id,assignment_id,school_id) VALUES(150,10,140,101)");
        }
        boolean decision(long tenant,long epoch,long identity) {
            var access=new AccessContext(tenant,1,epoch,identity);
            try(var scope=AccessContextHolder.open(access)) {
                return Boolean.TRUE.equals(new TransactionTemplate(manager).execute(s->new ManagementAuthority().allows(
                        EntityManagerFactoryUtils.getTransactionalEntityManager(factory),access,"MANAGE_ORGANIZATIONS")));
            }
        }
        boolean decision() { return decision(10,1,1); }
    }

    @Test void membershipAndAdministratorRoleNameDoNotGrantManagement() {
        fixture("authoritydefault",f->{f.org();f.assignment();assertThat(f.decision()).isFalse();});
    }
    @Test void personalGrantEnablesRealOrganizationAndAuditTransaction() {
        fixture("authoritykernel",f->{
            f.grant(160,120,"ALLOW");var authority=new ManagementAuthority();
            var kernel=new OrganizationTransactionKernel(f.factory,f.manager,authority,new OrganizationAuditAppender(f.factory,()->"real-authority"),Clock.systemUTC(),128);
            try(var scope=AccessContextHolder.open(new AccessContext(10,1,1,1))) {
                kernel.create(new OrganizationTransactionKernel.Create(OrganizationNode.Kind.DEPARTMENT,"Department",null,null,null,OrganizationNode.Status.ACTIVE));
            }
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM de_ent_audit_event",Integer.class)).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT access_epoch FROM de_ent_tenant WHERE id=10",Long.class)).isEqualTo(2);
        });
    }
    @Test void explicitOrganizationGrantRequiresExactActiveMembership() {
        fixture("authorityorg",f->{f.grant(160,121,"ALLOW");assertThat(f.decision()).isFalse();f.org();assertThat(f.decision()).isTrue();
            f.jdbc.update("UPDATE de_ent_org_member SET status='DISABLED' WHERE id=130");assertThat(f.decision()).isFalse();});
    }
    @Test void disabledOrganizationAncestorDoesNotConferManagement() {
        fixture("authorityancestor",f->{f.org();f.grant(160,121,"ALLOW");
            f.jdbc.update("INSERT INTO de_ent_org(id,tenant_id,kind,name,status) VALUES(102,10,'DEPARTMENT','Root','DISABLED')");
            f.jdbc.update("UPDATE de_ent_org SET parent_id=102 WHERE id=101");assertThat(f.decision()).isFalse();});
    }
    @Test void roleGrantRequiresItsOwnActiveAssignmentAndSchools() {
        fixture("authorityrole",f->{f.grant(160,122,"ALLOW");assertThat(f.decision()).isFalse();f.assignment();assertThat(f.decision()).isTrue();
            f.jdbc.update("UPDATE de_ent_role_assignment SET status='DISABLED' WHERE id=140");assertThat(f.decision()).isFalse();});
    }
    @Test void emptyDisabledOrNonSchoolRoleScopeRejects() {
        fixture("authorityschools",f->{f.grant(160,122,"ALLOW");f.assignment();
            f.jdbc.update("DELETE FROM de_ent_assignment_school WHERE id=150");assertThat(f.decision()).isFalse();
            f.jdbc.update("INSERT INTO de_ent_assignment_school(id,tenant_id,assignment_id,school_id) VALUES(150,10,140,101)");
            f.jdbc.update("UPDATE de_ent_org SET status='DISABLED' WHERE id=101");assertThat(f.decision()).isFalse();
            f.jdbc.update("INSERT INTO de_ent_org(id,tenant_id,kind,name,status) VALUES(103,10,'DEPARTMENT','Not school','ACTIVE')");
            f.jdbc.update("UPDATE de_ent_assignment_school SET school_id=103 WHERE id=150");assertThat(f.decision()).isFalse();});
    }
    @Test void explicitPersonalDenyOverridesOrganizationAllow() {
        fixture("authoritydeny",f->{f.org();f.grant(160,121,"ALLOW");f.grant(161,120,"DENY");assertThat(f.decision()).isFalse();});
    }
    @Test void roleDenyOverridesPersonalAllowWithoutCombiningSchoolSets() {
        fixture("authorityroledeny",f->{f.assignment();f.grant(160,122,"DENY");f.grant(161,120,"ALLOW");assertThat(f.decision()).isFalse();
            f.jdbc.update("UPDATE de_ent_role SET status='DISABLED' WHERE id=110");assertThat(f.decision()).isTrue();});
    }
    @Test void otherGroupAndDisabledMemberCannotUseGrants() {
        fixture("authoritygroups",f->{f.grant(160,120,"ALLOW");assertThat(f.decision()).isTrue();assertThat(f.decision(20,1,1)).isFalse();
            f.jdbc.update("UPDATE de_ent_tenant_member SET status='DISABLED' WHERE id=100");assertThat(f.decision()).isFalse();});
    }
    @Test void staleIdentityTenantRevisionAndDisabledUserReject() {
        fixture("authorityepoch",f->{f.grant(160,120,"ALLOW");assertThat(f.decision(10,2,1)).isFalse();assertThat(f.decision(10,1,2)).isFalse();
            f.jdbc.update("UPDATE de_ent_user SET status='DISABLED' WHERE id=1");assertThat(f.decision()).isFalse();});
    }
    @Test void missingContextAndMissingTransactionReject() {
        fixture("authoritycontext",f->{var authority=new ManagementAuthority();var access=new AccessContext(10,1,1,1);
            assertThatThrownBy(()->authority.allows(null,access,"MANAGE_ORGANIZATIONS")).isInstanceOf(DEException.class);
            try(var scope=AccessContextHolder.open(access)) {assertThatThrownBy(()->authority.allows(null,access,"MANAGE_ORGANIZATIONS")).isInstanceOf(IllegalStateException.class);}});
    }
    @Test void formalV43UpgradeRetainsRowsRepeatsAndRejectsCrossGroupSubject() {
        var jdbc=FoundationMigrationTest.fresh("authorityupgrade");new EnterpriseFoundationSqlBlock(jdbc).execute();new EnterpriseAuditSqlBlock(jdbc).execute();
        jdbc.update("INSERT INTO de_ent_user(id,username,display_name) VALUES(1,'retained','Retained')");
        var migration=new EnterpriseAuthoritySqlBlock(jdbc);migration.execute();migration.execute();new FoundationSchemaVerifier(jdbc,FoundationSchemaV43.TABLES).run(null);
        assertThat(jdbc.queryForObject("SELECT username FROM de_ent_user WHERE id=1",String.class)).isEqualTo("retained");
        jdbc.update("INSERT INTO de_ent_tenant(id,code,name) VALUES(10,'A','A'),(20,'B','B')");
        jdbc.update("INSERT INTO de_ent_tenant_member(id,tenant_id,user_id) VALUES(100,10,1)");
        assertThatThrownBy(()->jdbc.update("INSERT INTO de_ent_subject(id,tenant_id,subject_type,member_id) VALUES(120,20,'USER',100)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO de_ent_subject(id,tenant_id,subject_type) VALUES(121,10,'USER')"))
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class, failure -> {
                    var cause=(java.sql.SQLException)failure.getRootCause();
                    assertThat(cause.getErrorCode()).isEqualTo(3819);
                    assertThat(cause.getMessage()).contains("ck_subject_branch");
                });
    }
}
