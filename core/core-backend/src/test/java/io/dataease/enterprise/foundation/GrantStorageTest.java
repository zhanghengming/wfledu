package io.dataease.enterprise.foundation;

import io.dataease.dao.auto.entity.DeStandaloneVersion;
import io.dataease.enterprise.identity.persistence.FoundationStatus;
import io.dataease.enterprise.permission.persistence.EnterpriseGrant;
import io.dataease.enterprise.permission.persistence.EnterpriseGrantSchool;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;

class GrantStorageTest {
    @BeforeAll static void guard() throws Exception { FoundationMigrationTest.guardedPrivateConnection(); }
    private static void fixture(String name,java.util.function.Consumer<PermissionStorageFixture.Fixture> action) {
        PermissionStorageFixture.use(name,List.of(EnterpriseGrant.class,EnterpriseGrantSchool.class),action);
    }
    private static void migrate(PermissionStorageFixture.Fixture f) { f.add("v46",new EnterpriseGrantSqlBlock(f.jdbc()));f.run(); }
    private static void rule(JdbcTemplate jdbc,long id,long tenant,long subject,Long resource,String effect) {
        jdbc.update("INSERT INTO de_ent_grant(id,tenant_id,subject_id,policy_kind,resource_type,resource_scope_kind,resource_id,action,effect,school_scope_kind) VALUES(?,?,?,'DATA_ACCESS','DATASET',?,?,'VIEW',?,'EXPLICIT')",
                id,tenant,subject,resource==null?"ALL_DATASETS_IN_TENANT":"EXACT",resource,effect);
    }
    @Test void sixStepEmptyPlanAndRepeatHaveNoDefaultBusinessGrants() {
        fixture("grantempty",f -> {migrate(f);f.run();new FoundationSchemaVerifier(f.jdbc(),FoundationSchemaV46.TABLES).run(null);
            assertThat(f.versions().findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.6","4.5","4.4","4.3","4.2","4.1");
            assertThat(f.versions().findRecords()).extracting(DeStandaloneVersion::getSuccess).containsOnly(true);
            for (var t : FoundationSchemaV46.TABLES) assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM `"+t.name()+"`",Long.class)).isZero();
        });
    }
    @Test void seededV45UpgradeRetainsEveryOldRowAndSuccessfulHistory() {
        fixture("grantupgrade",f -> {f.run();PermissionStorageFixture.seed(f.jdbc());
            var before = PermissionStorageFixture.snapshot(f.jdbc(),FoundationSchemaV45.TABLES);
            var history = f.versions().findRecords().stream().map(v -> List.of(v.getId(),v.getVersion(),v.getSuccess())).toList();
            migrate(f);f.run();new FoundationSchemaVerifier(f.jdbc(),FoundationSchemaV46.TABLES).run(null);
            assertThat(PermissionStorageFixture.snapshot(f.jdbc(),FoundationSchemaV45.TABLES)).isEqualTo(before);
            assertThat(f.versions().findRecords().subList(1,6).stream().map(v -> List.of(v.getId(),v.getVersion(),v.getSuccess())).toList()).isEqualTo(history);
            assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM de_ent_grant",Long.class)).isZero();
        });
    }
    @Test void committedGrantDdlFailureRetainsLedgerAndResumesMissingSchoolTable() {
        fixture("grantretry",f -> {f.run();PermissionStorageFixture.seed(f.jdbc());
            var before = PermissionStorageFixture.snapshot(f.jdbc(),FoundationSchemaV45.TABLES);
            var interrupted = spy(f.jdbc());
            doAnswer(call -> {String sql=call.getArgument(0);f.jdbc().execute(sql);
                if(sql.startsWith("CREATE TABLE `de_ent_grant`")) throw new IllegalStateException("Synthetic committed grant interruption");
                return null;}).when(interrupted).execute(anyString());
            f.add("v46",new EnterpriseGrantSqlBlock(interrupted));
            assertThatThrownBy(f::run).isInstanceOf(RuntimeException.class).hasMessageContaining("4.6");
            assertThat(f.versions().findRecords().getFirst().getSuccess()).isFalse();
            assertThat(FoundationSchema.exists(f.jdbc(),FoundationSchemaV46.ADDITIONS.getFirst())).isTrue();
            assertThat(FoundationSchema.exists(f.jdbc(),FoundationSchemaV46.ADDITIONS.get(1))).isFalse();
            f.add("v46",new EnterpriseGrantSqlBlock(f.jdbc()));f.run();f.run();new FoundationSchemaVerifier(f.jdbc(),FoundationSchemaV46.TABLES).run(null);
            assertThat(f.versions().findRecords()).extracting(DeStandaloneVersion::getVersion).containsExactly("4.6","4.6","4.5","4.4","4.3","4.2","4.1");
            assertThat(f.versions().findRecords()).extracting(DeStandaloneVersion::getSuccess).containsExactly(true,false,true,true,true,true,true);
            assertThat(PermissionStorageFixture.snapshot(f.jdbc(),FoundationSchemaV45.TABLES)).isEqualTo(before);
        });
    }
    @Test void existingExpressionDriftRefusesBeforeCreatingOtherTableAndWithoutRepair() {
        fixture("grantdrift",f -> {f.run();PermissionStorageFixture.seed(f.jdbc());
            f.jdbc().execute(FoundationSchemaV46.ADDITIONS.getFirst().ddl().replace("coalesce(`resource_id`,0)","coalesce(`resource_id`,1)"));
            var before=f.jdbc().queryForMap("SHOW CREATE TABLE de_ent_grant");
            f.add("v46",new EnterpriseGrantSqlBlock(f.jdbc()));
            assertThatThrownBy(f::run).isInstanceOf(RuntimeException.class).hasMessageContaining("4.6");
            assertThat(f.jdbc().queryForMap("SHOW CREATE TABLE de_ent_grant")).isEqualTo(before);
            assertThat(FoundationSchema.exists(f.jdbc(),FoundationSchemaV46.ADDITIONS.get(1))).isFalse();
            assertThat(f.versions().findRecords().getFirst().getSuccess()).isFalse();
        });
    }
    @Test void subjectsAndTypedResourcesRejectBothCrossGroupDirections() {
        fixture("grantforeign",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());
            for (long tenant : new long[]{10,20}) {
                long own=tenant==10?13:23,foreign=tenant==10?23:13,ownResource=tenant==10?1001:2001,foreignResource=tenant==10?2001:1001,wrongType=tenant==10?1002:2002;
                PermissionStorageFixture.integrity(() -> rule(f.jdbc(),3001,tenant,foreign,ownResource,"ALLOW"),1452,1216);
                PermissionStorageFixture.integrity(() -> rule(f.jdbc(),3001,tenant,own,foreignResource,"ALLOW"),1452,1216);
                PermissionStorageFixture.integrity(() -> rule(f.jdbc(),3001,tenant,own,wrongType,"ALLOW"),1452,1216);
            }
            assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM de_ent_grant",Long.class)).isZero();
        });
    }
    @Test void nullResourceNaturalKeyIsUniqueAndAllowsIndependentDeny() {
        fixture("grantnatural",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());
            rule(f.jdbc(),3001,10,13,null,"ALLOW");rule(f.jdbc(),3002,10,13,null,"DENY");rule(f.jdbc(),4001,20,23,null,"ALLOW");
            assertThat(f.jdbc().queryForList("SELECT resource_key FROM de_ent_grant ORDER BY id",Long.class)).containsExactly(0L,0L,0L);
            assertThat(f.jdbc().queryForList("SELECT status FROM de_ent_grant ORDER BY id",String.class)).containsOnly("DISABLED");
            PermissionStorageFixture.integrity(() -> rule(f.jdbc(),3003,10,13,null,"ALLOW"),1062);
            rule(f.jdbc(),3004,10,13,1001L,"ALLOW");
        });
    }
    @Test void invalidPolicyActionsScopesAndNullableBranchesCannotPassChecks() {
        fixture("grantinvalid",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());rule(f.jdbc(),3001,10,13,1001L,"ALLOW");
            for (String update : List.of("policy_kind='ADMIN'","policy_kind='RESOURCE_ACTION'","action='EDIT'","school_scope_kind='NONE'","school_scope_kind='UNKNOWN'",
                    "effect='allow'","status='active'","resource_id=NULL","resource_id=0","resource_scope_kind='ALL_DATASETS_IN_TENANT'","subject_id=0","version=0")) {
                PermissionStorageFixture.integrity(() -> f.jdbc().update("UPDATE de_ent_grant SET "+update+" WHERE id=3001"),3819,1452,1216);
            }
            f.jdbc().update("INSERT INTO de_ent_grant(id,tenant_id,subject_id,policy_kind,resource_type,resource_id,action,effect,school_scope_kind) VALUES(3002,10,13,'RESOURCE_ACTION','DASHBOARD',1002,'EDIT','ALLOW','NONE')");
            assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM de_ent_grant",Long.class)).isEqualTo(2);
        });
    }
    @Test void schoolAssociationsRejectBothForeignDirectionsDuplicatesAndUnknownSchools() {
        fixture("grantschool",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());rule(f.jdbc(),3001,10,13,1001L,"ALLOW");rule(f.jdbc(),4001,20,23,2001L,"ALLOW");
            for (long tenant : new long[]{10,20}) {
                long ownGrant=tenant==10?3001:4001,foreignGrant=tenant==10?4001:3001,ownSchool=tenant==10?101:201,foreignSchool=tenant==10?201:101;
                PermissionStorageFixture.integrity(() -> f.jdbc().update("INSERT INTO de_ent_grant_school(id,tenant_id,grant_id,school_id) VALUES(5001,?,?,?)",tenant,ownGrant,foreignSchool),1452,1216);
                PermissionStorageFixture.integrity(() -> f.jdbc().update("INSERT INTO de_ent_grant_school(id,tenant_id,grant_id,school_id) VALUES(5001,?,?,?)",tenant,foreignGrant,ownSchool),1452,1216);
            }
            f.jdbc().update("INSERT INTO de_ent_grant_school(id,tenant_id,grant_id,school_id) VALUES(5001,10,3001,101)");
            PermissionStorageFixture.integrity(() -> f.jdbc().update("INSERT INTO de_ent_grant_school(id,tenant_id,grant_id,school_id) VALUES(5002,10,3001,101)"),1062);
            PermissionStorageFixture.integrity(() -> f.jdbc().update("INSERT INTO de_ent_grant_school(id,tenant_id,grant_id,school_id) VALUES(5002,10,3001,999)"),1452,1216);
            PermissionStorageFixture.integrity(() -> f.jdbc().update("DELETE FROM de_ent_grant WHERE id=3001"),1451,1217);
        });
    }
    private static EnterpriseGrant entity(long id) {
        var row=new EnterpriseGrant();row.setId(id);row.setTenantId(10L);row.setSubjectId(13L);
        row.setPolicyKind("DATA_ACCESS");row.setResourceType("DATASET");row.setResourceScopeKind("ALL_DATASETS_IN_TENANT");
        row.setAction("VIEW");row.setEffect("ALLOW");row.setSchoolScopeKind("EXPLICIT");
        var now=LocalDateTime.of(2026,10,8,0,0);row.setCreatedAt(now);row.setUpdatedAt(now);row.setCreatedBy(1L);row.setUpdatedBy(1L);return row;
    }
    @Test void jpaGeneratedSlotIsReadOnlyAndNaturalIdentityCannotBeReplaced() {
        fixture("grantjpa",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());
            try (var em=f.factory().createEntityManager()) {
                em.getTransaction().begin();var row=entity(3001);ReflectionTestUtils.setField(row,"resourceKey",999L);
                em.persist(row);em.flush();em.refresh(row);assertThat(row.getResourceKey()).isZero();
                ReflectionTestUtils.setField(row,"resourceKey",999L);row.setResourceId(1001L);row.setSubjectId(23L);row.setEffect("DENY");row.setStatus(FoundationStatus.ACTIVE);
                em.flush();em.clear();var reloaded=em.find(EnterpriseGrant.class,3001L);
                assertThat(reloaded.getResourceKey()).isZero();assertThat(reloaded.getResourceId()).isNull();
                assertThat(reloaded.getSubjectId()).isEqualTo(13L);assertThat(reloaded.getEffect()).isEqualTo("ALLOW");
                assertThat(reloaded.getStatus()).isEqualTo(FoundationStatus.ACTIVE);em.getTransaction().commit();
            }
            assertThat(f.jdbc().queryForObject("SELECT resource_key FROM de_ent_grant WHERE id=3001",Long.class)).isZero();
        });
    }
    @Test void jpaCasAndExplicitSchoolClearReloadOutsidePersistenceContext() {
        fixture("grantcas",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());rule(f.jdbc(),3001,10,13,1001L,"ALLOW");
            f.jdbc().update("INSERT INTO de_ent_grant_school(id,tenant_id,grant_id,school_id) VALUES(5001,10,3001,101)");
            try (var first=f.factory().createEntityManager();var second=f.factory().createEntityManager()) {
                first.getTransaction().begin();second.getTransaction().begin();
                var a=first.find(EnterpriseGrant.class,3001L);var b=second.find(EnterpriseGrant.class,3001L);
                a.setStatus(FoundationStatus.ACTIVE);first.getTransaction().commit();
                b.setStatus(FoundationStatus.ACTIVE);
                assertThatThrownBy(() -> second.flush()).isInstanceOf(OptimisticLockException.class);
                second.getTransaction().rollback();
            }
            try (var em=f.factory().createEntityManager()) {
                em.getTransaction().begin();
                assertThat(em.createQuery("update EnterpriseGrant set version=version+1,status=:status where tenantId=:tenant and id=:id and version=:version")
                        .setParameter("status",FoundationStatus.DISABLED).setParameter("tenant",10L).setParameter("id",3001L).setParameter("version",2L).executeUpdate()).isEqualTo(1);
                assertThat(em.createQuery("delete from EnterpriseGrantSchool where tenantId=:tenant and grantId=:grant").setParameter("tenant",10L).setParameter("grant",3001L).executeUpdate()).isEqualTo(1);
                em.clear();assertThat(em.find(EnterpriseGrant.class,3001L).getVersion()).isEqualTo(3L);em.getTransaction().commit();
            }
            assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM de_ent_grant_school",Long.class)).isZero();
        });
    }
    @Test void multiTableFlushFailureRollsBackGrantSchoolsAndTenantRevision() {
        fixture("grantrollback",f -> {migrate(f);PermissionStorageFixture.seed(f.jdbc());
            try (var em=f.factory().createEntityManager()) {
                em.getTransaction().begin();var grant=entity(3001);em.persist(grant);
                var school=new EnterpriseGrantSchool();school.setId(5001L);school.setTenantId(10L);school.setGrantId(3001L);school.setSchoolId(101L);
                school.setCreatedAt(grant.getCreatedAt());school.setUpdatedAt(grant.getUpdatedAt());em.persist(school);
                em.flush();em.createNativeQuery("UPDATE de_ent_tenant SET access_epoch=access_epoch+1 WHERE id=10").executeUpdate();
                em.getTransaction().rollback();
            }
            assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM de_ent_grant",Long.class)).isZero();
            assertThat(f.jdbc().queryForObject("SELECT COUNT(*) FROM de_ent_grant_school",Long.class)).isZero();
            assertThat(f.jdbc().queryForObject("SELECT access_epoch FROM de_ent_tenant WHERE id=10",Long.class)).isEqualTo(1L);
        });
    }
    @Test void unknownReservedObjectsAndOldFiveStepPlanRejectWithoutChangingHistory() {
        fixture("grantreserved",f -> {migrate(f);var before=f.versions().findRecords().stream().map(v->List.of(v.getId(),v.getVersion(),v.getSuccess())).toList();
            f.remove("v46");assertThatThrownBy(f::run).isInstanceOf(IllegalStateException.class);
            assertThat(f.versions().findRecords().stream().map(v->List.of(v.getId(),v.getVersion(),v.getSuccess())).toList()).isEqualTo(before);
            new FoundationSchemaVerifier(f.jdbc(),FoundationSchemaV46.TABLES).run(null);
            PermissionStorageFixture.reservedView(f.jdbc());
            assertThatThrownBy(() -> new FoundationSchemaVerifier(f.jdbc(),FoundationSchemaV46.TABLES).run(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("undeclared");
            assertThat(f.jdbc().queryForObject("SELECT TABLE_TYPE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='de_ent_undeclared_grant_probe'",String.class)).isEqualTo("VIEW");
        });
    }
}
